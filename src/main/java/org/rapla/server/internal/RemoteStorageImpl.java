package org.rapla.server.internal;

import org.rapla.RaplaResources;
import org.rapla.components.util.ParseDateException;
import org.rapla.components.util.SerializableDateTimeFormat;
import org.rapla.components.xmlbundle.I18nBundle;
import org.rapla.entities.DependencyException;
import org.rapla.entities.Entity;
import org.rapla.entities.EntityNotFoundException;
import org.rapla.entities.RaplaType;
import org.rapla.entities.User;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.domain.internal.AllocatableImpl;
import org.rapla.entities.domain.internal.AppointmentImpl;
import org.rapla.entities.domain.internal.ReservationImpl;
import org.rapla.entities.dynamictype.Classifiable;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.internal.DynamicTypeImpl;
import org.rapla.entities.storage.EntityReferencer;
import org.rapla.entities.storage.ReferenceInfo;
import org.rapla.facade.Conflict;
import org.rapla.facade.internal.ConflictImpl;
import org.rapla.framework.RaplaException;
import org.rapla.framework.internal.AbstractRaplaLocale;
import org.rapla.inject.DefaultImplementation;
import org.rapla.inject.InjectionContext;
import org.rapla.logger.Logger;
import org.rapla.plugin.mail.MailPlugin;
import org.rapla.plugin.mail.server.MailInterface;
import org.rapla.scheduler.Promise;
import org.rapla.scheduler.ResolvedPromise;
import org.rapla.scheduler.sync.SynchronizedCompletablePromise;
import org.rapla.server.AuthenticationStore;
import org.rapla.server.PrePostDispatchProcessor;
import org.rapla.server.RemoteSession;
import org.rapla.storage.CachableStorageOperator;
import org.rapla.storage.PermissionController;
import org.rapla.storage.PreferencePatch;
import org.rapla.storage.RaplaNewVersionException;
import org.rapla.storage.RaplaSecurityException;
import org.rapla.storage.StorageOperator;
import org.rapla.storage.UpdateEvent;
import org.rapla.storage.dbrm.AppointmentMap;
import org.rapla.storage.dbrm.RemoteStorage;
import org.rapla.storage.impl.EntityStore;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@DefaultImplementation(context = InjectionContext.server, of = RemoteStorage.class) public class RemoteStorageImpl implements RemoteStorage
{
    @Inject RemoteSession session;
    @Inject CachableStorageOperator operator;
    @Inject SecurityManager security;
    @Inject ShutdownService shutdownService;
    @Inject Set<PrePostDispatchProcessor> prePostDispatchProcessors;

    @Inject Set<AuthenticationStore> authenticationStore;

    @Inject RaplaResources i18n;
    @Inject Provider<MailInterface> mailInterface;
    @Inject UpdateDataManager updateDataManager;
    private final HttpServletRequest request;

    @Inject public RemoteStorageImpl(@Context HttpServletRequest request)
    {
        this.request = request;
    }

    @Override
    public UpdateEvent getResources() throws RaplaException
    {
        User user = checkSessionUser();
        getLogger().debug("A RemoteAuthentificationService wants to get all resource-objects.");
        Date serverTime = operator.getCurrentTimestamp();
        Collection<Entity> visibleEntities = operator.getVisibleEntities(user);
        UpdateEvent evt = new UpdateEvent();
        evt.setUserId(user.getId());
        for (Entity entity : visibleEntities)
        {
            if (UpdateDataManagerImpl.isTransferedToClient(entity))
            {
                if (entity instanceof Preferences)
                {
                    Preferences preferences = (Preferences) entity;
                    ReferenceInfo<User> ownerId = preferences.getOwnerRef();
                    if (ownerId == null && !user.isAdmin())
                    {
                        entity = UpdateDataManagerImpl.removeServerOnlyPreferences(preferences);
                    }
                }
                evt.addStore(entity);
            }
        }
        evt.setLastValidated(serverTime);
        return evt;
    }

    @Override
    public Promise<UpdateEvent> getResourcesAsync()
    {
        try
        {
            final UpdateEvent resources = getResources();
            return new ResolvedPromise<UpdateEvent>(resources);
        }
        catch (Exception ex)
        {
            return new ResolvedPromise<UpdateEvent>(ex);
        }
    }

    @Override
    public UpdateEvent getEntityRecursive(UpdateEvent.SerializableReferenceInfo... ids) throws RaplaException
    {
        User sessionUser = checkSessionUser();
        Date repositoryVersion = operator.getCurrentTimestamp();

        ArrayList<Entity> completeList = new ArrayList<Entity>();
        for (UpdateEvent.SerializableReferenceInfo id : ids)
        {
            final ReferenceInfo reference = id.getReference();
            Entity entity = operator.resolve(reference);
            if (entity instanceof Classifiable)
            {
                if (!DynamicTypeImpl.isTransferedToClient((Classifiable) entity))
                {
                    throw new RaplaSecurityException("Entity for id " + id + " is not transferable to the client");
                }
            }
            if (entity instanceof DynamicType)
            {
                if (!DynamicTypeImpl.isTransferedToClient((DynamicType) entity))
                {
                    throw new RaplaSecurityException("Entity for id " + id + " is not transferable to the client");
                }
            }
            if (entity instanceof Reservation)
            {
                entity = checkAndMakeReservationsAnonymous(sessionUser, entity);
            }
            if (entity instanceof Preferences)
            {
                entity = UpdateDataManagerImpl.removeServerOnlyPreferences((Preferences) entity);
            }
            security.checkRead(sessionUser, entity);
            completeList.add(entity);
            getLogger().debug("Get entity " + entity);
        }
        UpdateEvent evt = new UpdateEvent();
        evt.setLastValidated(repositoryVersion);
        for (Entity entity : completeList)
        {
            evt.addStore(entity);
        }
        return evt;
    }

    @Override
    public Promise<UpdateEvent> getEntityRecursiveAsync(UpdateEvent.SerializableReferenceInfo... ids)  {
        try {
            return new ResolvedPromise<>(getEntityRecursive(ids));
        }
        catch (RaplaException ex)
        {
            return new ResolvedPromise<>(ex);
        }
    }

    @Override public Promise<AppointmentMap> queryAppointments(QueryAppointments job) throws RaplaException
    {
        User sessionUser = checkSessionUser();
        String[] allocatableIds = job.getResources();
        Date start = job.getStart();
        Date end = job.getEnd();
        Map<String, String> annotationQuery = job.getAnnotations();
        getLogger().debug("A RemoteAuthentificationService wants to reservations from ." + start + " to " + end);
        User user = null;
        // Reservations and appointments
        List<Allocatable> allocatables = new ArrayList<Allocatable>();
        if (allocatableIds != null)
        {
            for (String id : allocatableIds)
            {
                Allocatable allocatable = operator.resolve(id, Allocatable.class);
                security.checkRead(sessionUser, allocatable);
                allocatables.add(allocatable);
            }
        }
        ClassificationFilter[] classificationFilters = null;
        final Promise<Map<Allocatable, Collection<Appointment>>> mapFutureResult = operator
                .queryAppointments(user, allocatables, start, end, classificationFilters, annotationQuery);
        Map<Allocatable, Collection<Appointment>> reservations = operator.waitForWithRaplaException(mapFutureResult, 50000);
        AppointmentMap list = new AppointmentMap(reservations);
        getLogger().debug("Get reservations " + start + " " + end + ": " + reservations.size() + "," + list.toString());
        return new ResolvedPromise<AppointmentMap>(list);
    }

    private ReservationImpl checkAndMakeReservationsAnonymous(User sessionUser, Entity entity)
    {
        ReservationImpl reservation = (ReservationImpl) entity;
        PermissionController permissionController = operator.getPermissionController();
        boolean reservationVisible = permissionController.canRead(reservation, sessionUser);
        // check if the user is allowed to read the reservation info
        if (!reservationVisible)
        {
            ReservationImpl clone = reservation.clone();
            // we can safely change the reservation info here because we cloned it in transaction safe before
            DynamicType anonymousReservationType = operator.getDynamicType(StorageOperator.ANONYMOUSEVENT_TYPE);
            clone.setClassification(anonymousReservationType.newClassification());
            clone.setReadOnly();
            return clone;
        }
        else
        {
            return reservation;
        }
    }

    public void restartServer() throws RaplaException
    {
        final User user = checkSessionUser();
        if (!user.isAdmin())
            throw new RaplaSecurityException("Only admins can restart the server");

        shutdownService.shutdown(true);
    }

    public UpdateEvent store(UpdateEvent event) throws RaplaException
    {
        User sessionUser = checkSessionUser();
        Date currentTimestamp = operator.getCurrentTimestamp();
        Date lastSynced = event.getLastValidated();
        if (lastSynced == null)
        {
            throw new RaplaException("client sync time is missing");
        }
        if (lastSynced.after(currentTimestamp))
        {
            long diff = lastSynced.getTime() - currentTimestamp.getTime();
            getLogger().warn("Timestamp of client " + diff + " ms  after server ");
            lastSynced = currentTimestamp;
        }
        //   LocalCache cache = operator.getCache();
        //   UpdateEvent event = createUpdateEvent( context,xml, cache );
        getLogger().info("Dispatching change for user " + sessionUser);
        if (sessionUser != null)
        {
            event.setUserId(sessionUser.getId());
        }
        dispatch_(event);
        getLogger().info("Change for user " + sessionUser + " dispatched.");

        UpdateEvent result = updateDataManager.createUpdateEvent(sessionUser, lastSynced);
        for ( PrePostDispatchProcessor processor:prePostDispatchProcessors)
        {
            processor.postProcess(sessionUser, result);
        }
        return result;
    }

    public Promise<UpdateEvent> dispatch(UpdateEvent event)
    {
        try {
            final UpdateEvent result = store(event);
            return new ResolvedPromise<>(result);
        } catch (RaplaException ex) {
            return new ResolvedPromise<>(ex);
        }
    }

    public boolean canChangePassword() throws RaplaException
    {
        checkSessionUser();
        boolean result = operator.canChangePassword();
        return result;
    }

    public void changePassword(PasswordPost job) throws RaplaException
    {
        String username = job.getUsername();
        String oldPassword = job.getOldPassword();
        String newPassword = job.getNewPassword();
        User sessionUser = checkSessionUser();
        User user = operator.getUser(username);
        if (!PermissionController.canAdminUser(sessionUser, user))
        {
            if (authenticationStore != null && authenticationStore.size() > 0)
            {
                throw new RaplaSecurityException("Rapla can't change your password. Authentication handled by ldap plugin.");
            }
            operator.authenticate(username, new String(oldPassword));
        }
        operator.changePassword(user, oldPassword.toCharArray(), newPassword.toCharArray());
    }

    public void changeName(String username, String newTitle, String newSurename, String newLastname) throws RaplaException
    {
        User changingUser = checkSessionUser();
        User user = operator.getUser(username);
        if (changingUser.isAdmin() || user.equals(changingUser))
        {
            operator.changeName(user, newTitle, newSurename, newLastname);
        }
        else
        {
            throw new RaplaSecurityException("Not allowed to change email from other users");
        }
    }

    public void changeEmail(String username, String newEmail) throws RaplaException
    {
        User changingUser = checkSessionUser();
        User user = operator.getUser(username);
        if (changingUser.isAdmin() || user.equals(changingUser))
        {
            operator.changeEmail(user, newEmail);
        }
        else
        {
            throw new RaplaSecurityException("Not allowed to change email from other users");
        }
    }

    public String getUsername(String userId) throws RaplaException
    {
        checkSessionUser();
        String username = operator.getUsername(new ReferenceInfo<User>(userId, User.class));
        return username;
    }

    public void confirmEmail(String username, String newEmail) throws RaplaException
    {
        User changingUser = checkSessionUser();
        User user = operator.getUser(username);
        if (changingUser.isAdmin() || user.equals(changingUser))
        {
            String subject = getString("security_code");
            Preferences prefs = operator.getPreferences(null, true);
            String mailbody = "" + getString("send_code_mail_body_1") + user.getUsername() + ",\n\n" + getString("send_code_mail_body_2") + "\n\n" + getString(
                    "security_code") + Math.abs(user.getEmail().hashCode()) + "\n\n" + getString("send_code_mail_body_3") + "\n\n"
                    + "-----------------------------------------------------------------------------------" + "\n\n" + getString("send_code_mail_body_4")
                    + prefs.getEntryAsString(AbstractRaplaLocale.TITLE, getString("rapla.title")) + " " + getString("send_code_mail_body_5");

            final MailInterface mail = mailInterface.get();
            final String defaultSender = prefs.getEntryAsString(MailPlugin.DEFAULT_SENDER_ENTRY, "");

            mail.sendMail(defaultSender, newEmail, subject, "" + mailbody);
        }
        else
        {
            throw new RaplaSecurityException("Not allowed to change email from other users");
        }
    }

    private String getString(String key)
    {
        return getI18n().getString(key);
    }

    public I18nBundle getI18n()
    {
        return i18n;
    }

    public List<String> createIdentifier(String type, int count) throws RaplaException
    {
        checkSessionUser();
        Class<? extends Entity> typeClass = RaplaType.find(type);
        //User user =
        checkSessionUser(); //check if authenified
        ReferenceInfo[] refs = operator.createIdentifier(typeClass, count);
        List<String> result = new ArrayList<String>();
        for (ReferenceInfo ref : refs)
        {
            result.add(ref.getId());
        }
        return result;
    }

    public UpdateEvent refresh(String lastSyncedTime) throws RaplaException
    {
        final User user = checkSessionUser();
        try
        {
            Date clientRepoVersion = SerializableDateTimeFormat.INSTANCE.parseTimestamp(lastSyncedTime);
            UpdateEvent event = updateDataManager.createUpdateEvent(user, clientRepoVersion);
            return event;
        }
        catch (ParseDateException e)
        {
            throw new RaplaException("Illegal last synced date " + lastSyncedTime + " caused " + e.getMessage(), e);
        }
    }

    public Promise<UpdateEvent> refreshAsync(String lastSyncedTime)
    {
        try
        {
            return new ResolvedPromise<>(refresh(lastSyncedTime));
        }
        catch (RaplaException e)
        {
            return new ResolvedPromise<UpdateEvent>(e);
        }
    }

    public Logger getLogger()
    {
        return session.getLogger();
    }

    private User checkSessionUser() throws RaplaException
    {
        return session.checkAndGetUser(request);
    }

    private void dispatch_(UpdateEvent evt) throws RaplaException
    {
        try
        {
            User user;
            if (evt.getUserId() != null)
            {
                user = operator.resolve(evt.getUserId(), User.class);
            }
            else
            {
                user = checkSessionUser();
            }
            Collection<Entity> storeObjects = evt.getStoreObjects();
            EntityStore store = new EntityStore(operator);
            store.addAll(storeObjects);
            for (EntityReferencer references : evt.getEntityReferences())
            {
                references.setResolver(store);
            }
            for (Entity entity : storeObjects)
            {
                security.checkWritePermissions(user, entity);
            }
            List<PreferencePatch> preferencePatches = evt.getPreferencePatches();
            for (PreferencePatch patch : preferencePatches)
            {
                security.checkWritePermissions(user, patch);
            }
            Collection<ReferenceInfo> removeObjects = evt.getRemoveIds();
            for (ReferenceInfo id : removeObjects)
            {
                Entity entity = operator.tryResolve(id);
                if (entity != null)
                {
                    security.checkDeletePermissions(user, entity);
                }
            }

            if (this.getLogger().isDebugEnabled())
                this.getLogger().debug("Processing plugin-update processors " );
            for (PrePostDispatchProcessor processor:prePostDispatchProcessors)
            {
                processor.preProcess(user, evt);
            }
            if (this.getLogger().isDebugEnabled())
                this.getLogger().debug("Dispatching changes to " + operator.getClass());


            operator.dispatch(evt);
            if (this.getLogger().isDebugEnabled())
                this.getLogger().debug("Changes dispatched returning result.");
        }
        catch (DependencyException ex)
        {
            throw ex;
        }
        catch (RaplaNewVersionException ex)
        {
            throw ex;
        }
        catch (RaplaSecurityException ex)
        {
            this.getLogger().warn(ex.getMessage());
            throw ex;
        }
        catch (RaplaException ex)
        {
            this.getLogger().error(ex.getMessage(), ex);
            throw ex;
        }
        catch (Exception ex)
        {
            this.getLogger().error(ex.getMessage(), ex);
            throw new RaplaException(ex);
        }
        catch (Error ex)
        {
            this.getLogger().error(ex.getMessage(), ex);
            throw ex;
        }
    }

    public List<ConflictImpl> getConflicts() throws RaplaException
    {
        User sessionUser = checkSessionUser();
        Set<Entity> completeList = new HashSet<Entity>();
        Collection<Conflict> conflicts = operator.getConflicts(sessionUser);
        List<ConflictImpl> result = new ArrayList<ConflictImpl>();
        for (Conflict conflict : conflicts)
        {
            result.add((ConflictImpl) conflict);
            Entity conflictRef = conflict;
            completeList.add(conflictRef);
        }
        return result;
    }

    @Override public Promise<Date> getNextAllocatableDate(NextAllocatableDateRequest job)
    {
        String[] allocatableIds = job.getAllocatableIds();
        AppointmentImpl appointment = job.getAppointment();
        String[] reservationIds = job.getReservationIds();
        Integer worktimestartMinutes = job.getWorktimeStartMinutes();
        Integer worktimeendMinutes = job.getWorktimeEndMinutes();
        Integer[] excludedDays = job.getExcludedDays();
        Integer rowsPerHour = job.getRowsPerHour();
        List<Allocatable> allocatables;
        try
        {
            checkSessionUser();
            allocatables = resolveAllocatables(allocatableIds);
        }
        catch ( RaplaException ex)
        {
            return  new ResolvedPromise<Date>(ex);
        }
        Collection<Reservation> ignoreList = resolveReservations(reservationIds);
        final Promise<Date> nextAllocatableDate = operator
                .getNextAllocatableDate(allocatables, appointment, ignoreList, worktimestartMinutes, worktimeendMinutes, excludedDays, rowsPerHour);
        return nextAllocatableDate;

    }

    @Override public Promise<BindingMap> getFirstAllocatableBindings(AllocatableBindingsRequest job)
    {
        String[] allocatableIds = job.getAllocatableIds();
        List<AppointmentImpl> appointments = job.getAppointments();
        String[] reservationIds = job.getReservationIds();
        List<Allocatable> allocatables;
        try
        {
            checkSessionUser();
            //Integer[][] result = new Integer[allocatableIds.length][];
           allocatables = resolveAllocatables(allocatableIds);
        } catch ( RaplaException ex)
        {
            return new ResolvedPromise<BindingMap>(ex);
        }
        Collection<Reservation> ignoreList = resolveReservations(reservationIds);
        List<Appointment> asList = cast(appointments);
        Promise<BindingMap> promise = operator.getFirstAllocatableBindings(allocatables, asList, ignoreList).thenApply((bindings) ->
        {
            Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
            for (Allocatable alloc : bindings.keySet())
            {
                Collection<Appointment> apps = bindings.get(alloc);
                if (apps == null)
                {
                    apps = Collections.emptyList();
                }
                ArrayList<String> indexArray = new ArrayList<String>(apps.size());
                for (Appointment app : apps)
                {
                    for (Appointment app2 : appointments)
                    {
                        if (app2.equals(app))
                        {
                            indexArray.add(app.getId());
                        }
                    }
                }
                result.put(alloc.getId(), indexArray);
            }
            return new BindingMap(result);
        });
        return promise;
    }

    private List<Appointment> cast(List<AppointmentImpl> appointments)
    {
        List<Appointment> result = new ArrayList<Appointment>(appointments.size());
        for (Appointment app : appointments)
        {
            result.add(app);
        }
        return result;
    }

    @Override
    public Promise<List<ReservationImpl>> getAllAllocatableBindings(AllocatableBindingsRequest job)
    {
        String[] allocatableIds = job.getAllocatableIds();
        List<AppointmentImpl> appointments = job.getAppointments();
        String[] reservationIds = job.getReservationIds();
        List<Allocatable> allocatables;
        try
        {
            User user = checkSessionUser();
            //Integer[][] result = new Integer[allocatableIds.length][];
            allocatables = resolveAllocatables(allocatableIds);
        } catch ( RaplaException ex)
        {
            return new ResolvedPromise<>(ex);
        }
        Collection<Reservation> ignoreList = resolveReservations(reservationIds);
        List<Appointment> asList = cast(appointments);

        Promise<List<ReservationImpl>> promise = operator.getAllAllocatableBindings(allocatables, asList, ignoreList).thenApply((bindings) ->
        {
            Set<ReservationImpl> result = new HashSet<ReservationImpl>();
            for (Allocatable alloc : bindings.keySet())
            {
                Map<Appointment, Collection<Appointment>> appointmentBindings = bindings.get(alloc);
                for (Appointment app : appointmentBindings.keySet())
                {
                    Collection<Appointment> bound = appointmentBindings.get(app);
                    if (bound != null)
                    {
                        for (Appointment appointment : bound)
                        {
                            ReservationImpl reservation = (ReservationImpl) appointment.getReservation();
                            if (reservation != null)
                            {
                                result.add(reservation);
                            }
                        }
                    }
                }
            }
            return new ArrayList<ReservationImpl>(result);
        });
        return promise;
    }

    private List<Allocatable> resolveAllocatables(String[] allocatableIds) throws RaplaException, RaplaSecurityException
    {
        List<Allocatable> allocatables = new ArrayList<Allocatable>();
        User sessionUser = checkSessionUser();
        for (String id : allocatableIds)
        {
            Allocatable entity = operator.resolve(id, Allocatable.class);
            allocatables.add(entity);
            security.checkRead(sessionUser, entity);
        }
        return allocatables;
    }

    private Collection<Reservation> resolveReservations(String[] ignoreList)
    {
        Set<Reservation> ignoreConflictsWith = new HashSet<Reservation>();
        for (String reservationId : ignoreList)
        {
            try
            {
                Reservation entity = operator.resolve(reservationId, Reservation.class);
                ignoreConflictsWith.add(entity);
            }
            catch (EntityNotFoundException ex)
            {
                // Do nothing reservation not found and assumed new
            }
        }
        return ignoreConflictsWith;
    }

    @Override public UpdateEvent doMerge(MergeRequest job, String lastSyncedTime ) throws RaplaException
    {
        final User sessionUser = checkSessionUser();
        AllocatableImpl allocatable = job.getAllocatable();
        String[] allocatableIds = job.getAllocatableIds();
        security.checkWritePermissions(sessionUser, allocatable);
        final Set<ReferenceInfo<Allocatable>> allocReferences = new LinkedHashSet<>();
        for (final String allocId : allocatableIds)
            {
                        final ReferenceInfo<Allocatable> refInfo = new ReferenceInfo<Allocatable>(allocId, Allocatable.class);
            allocReferences.add(refInfo);
            // TODO check write permissions
                }
        final Promise<Allocatable> promise = operator.doMerge(allocatable, allocReferences, sessionUser);
        try {
                SynchronizedCompletablePromise.waitFor(promise, 20000,getLogger());
            } catch (RaplaException e) {
                throw e;
            } catch (Exception e)
        {
                    throw new RaplaException( e);
        }
        return refresh( lastSyncedTime);
    }

    //			public void logEntityNotFound(String logMessage,String... referencedIds)
    //			{
    //				StringBuilder buf = new StringBuilder();
    //				buf.append("{");
    //				for  (String id: referencedIds)
    //				{
    //					buf.append("{ id=");
    //					if ( id != null)
    //					{
    //						buf.append(id.toString());
    //						buf.append(": ");
    //						Entity refEntity = operator.tryResolve(id);
    //						if ( refEntity != null )
    //						{
    //							buf.append( refEntity.toString());
    //						}
    //						else
    //						{
    //							buf.append("NOT FOUND");
    //						}
    //					}
    //					else
    //					{
    //						buf.append( "is null");
    //					}
    //
    //					buf.append("},  ");
    //				}
    //				buf.append("}");
    //				getLogger().error("EntityNotFoundFoundExceptionOnClient "+ logMessage + " " + buf.toString());
    //				//return ResultImpl.VOID;
    //			}
}
