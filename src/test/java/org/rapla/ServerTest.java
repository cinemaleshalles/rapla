/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla;

import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.rapla.components.util.DateTools;
import org.rapla.entities.Category;
import org.rapla.entities.DependencyException;
import org.rapla.entities.Entity;
import org.rapla.entities.User;
import org.rapla.entities.configuration.CalendarModelConfiguration;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.Permission;
import org.rapla.entities.domain.Repeating;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.dynamictype.Attribute;
import org.rapla.entities.dynamictype.AttributeType;
import org.rapla.entities.dynamictype.Classification;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.ConstraintIds;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.DynamicTypeAnnotations;
import org.rapla.facade.CalendarSelectionModel;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.RaplaFacade;
import org.rapla.facade.ModificationModule;
import org.rapla.facade.QueryModule;
import org.rapla.facade.UserModule;
import org.rapla.facade.internal.CalendarModelImpl;
import org.rapla.framework.RaplaException;
import org.rapla.framework.RaplaLocale;
import org.rapla.framework.TypedComponentRole;
import org.rapla.framework.logger.Logger;
import org.rapla.plugin.weekview.WeekviewPlugin;
import org.rapla.server.internal.ServerContainerContext;
import org.rapla.server.internal.ServerServiceImpl;
import org.rapla.storage.PermissionController;
import org.rapla.storage.StorageOperator;
import org.rapla.test.util.DefaultPermissionControllerSupport;
import org.rapla.test.util.RaplaTestCase;

import javax.inject.Provider;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

@RunWith(JUnit4.class)
public class ServerTest
{

    protected ClientFacade clientFacade1;
    protected ClientFacade clientFacade2;
    Locale locale;

    private Server server;
    Logger logger;
    Provider<ClientFacade> clientFacadeProvider;
    protected RaplaLocale raplaLocale;
    protected ServerServiceImpl serverService;


    @Before public void setUp() throws Exception
    {
        logger = RaplaTestCase.initLoger();
        int port = 8052;
        ServerContainerContext container = createContext();
        serverService = (ServerServiceImpl)RaplaTestCase.createServer( logger, container);
        raplaLocale = serverService.getRaplaLocale();
        server = ServletTestBase.createServer( serverService, port);
        clientFacadeProvider = RaplaTestCase.createFacadeWithRemote(logger, port);
        clientFacade1 = clientFacadeProvider.get();
        clientFacade2 = clientFacadeProvider.get();
        clientFacade1.login("homer", "duffs".toCharArray());

        clientFacade2.login("homer", "duffs".toCharArray());
        locale = Locale.getDefault();
    }

    protected RaplaFacade getServerFacade()
    {
        return serverService.getFacade();
    }

    protected StorageOperator getServerOperator()
    {
        return serverService.getOperator();
    }

    public RaplaLocale getRaplaLocale()
    {
        return raplaLocale;
    }

    protected ServerContainerContext createContext() throws Exception
    {
        ServerContainerContext container = new ServerContainerContext();
        String xmlFile = "testdefault.xml";
        container.setFileDatasource(RaplaTestCase.getTestDataFile(xmlFile));
        return container;
    }

    @After public void tearDown() throws Exception
    {
        clientFacade1.logout();
        clientFacade2.logout();
        server.stop();
    }


    @Test
    public void testLoad() throws Exception
    {
        getRaplaFacade1().getAllocatables();
    }

    @Test
    public void testChangeReservation() throws Exception
    {
        Reservation r1 = getRaplaFacade1().newReservation();
        String typeKey = r1.getClassification().getType().getKey();
        r1.getClassification().setValue("name", "test-reservation");
        r1.addAppointment(getRaplaFacade1().newAppointment(getRaplaFacade1().today(), new Date()));
        getRaplaFacade1().store(r1);
        // Wait for the update
        getRaplaFacade2().refresh();

        Reservation r2 = findReservation(getRaplaFacade2(), typeKey, "test-reservation");
        Assert.assertEquals(1, r2.getAppointments().length);
        Assert.assertEquals(0, r2.getAllocatables().length);

        // Modify Reservation in first facade
        Reservation r1clone = getRaplaFacade1().edit(r2);
        r1clone.addAllocatable(getRaplaFacade1().getAllocatables()[0]);
        getRaplaFacade1().store(r1clone);
        // Wait for the update
        getRaplaFacade2().refresh();

        // test for modify in second facade
        Reservation persistant = getRaplaFacade2().getPersistant(r2);
        Assert.assertEquals(1, persistant.getAllocatables().length);
        clientFacade2.logout();
    }

    public RaplaFacade getRaplaFacade2()
    {
        return clientFacade2.getRaplaFacade();
    }

    @Test
    public void testChangeDynamicType() throws Exception
    {
        {
            Allocatable allocatable = getRaplaFacade1().getAllocatables()[0];
            Assert.assertEquals(3, allocatable.getClassification().getAttributes().length);
        }
        DynamicType type = getRaplaFacade1().getDynamicType("room");
        Attribute newAttribute;
        {
            newAttribute = getRaplaFacade1().newAttribute(AttributeType.CATEGORY);
            DynamicType typeEdit1 = getRaplaFacade1().edit(type);
            newAttribute.setConstraint(ConstraintIds.KEY_ROOT_CATEGORY, getRaplaFacade1().getUserGroupsCategory());
            newAttribute.setKey("test");
            newAttribute.getName().setName("en", "test");
            typeEdit1.addAttribute(newAttribute);
            getRaplaFacade1().store(typeEdit1);
        }

        {
            Allocatable newResource = getRaplaFacade1().newResource();
            newResource.setClassification(type.newClassification());
            newResource.getClassification().setValue("name", "test-resource");
            newResource.getClassification().setValue("test", getRaplaFacade1().getUserGroupsCategory().getCategories()[0]);
            getRaplaFacade1().store(newResource);
        }

        {
            getRaplaFacade2().refresh();
            // Dyn
            DynamicType typeInSecondFacade = getRaplaFacade2().getDynamicType("room");
            Attribute att = typeInSecondFacade.getAttribute("test");
            Assert.assertEquals("test", att.getKey());
            Assert.assertEquals(AttributeType.CATEGORY, att.getType());
            Assert.assertEquals(getRaplaFacade2().getUserGroupsCategory(), att.getConstraint(ConstraintIds.KEY_ROOT_CATEGORY));

            ClassificationFilter filter = typeInSecondFacade.newClassificationFilter();
            filter.addEqualsRule("name", "test-resource");
            Allocatable newResource = getRaplaFacade2().getAllocatables(filter.toArray())[0];
            Classification classification = newResource.getClassification();
            Category userGroup = (Category) classification.getValue("test");
            final Category[] usergroups = getRaplaFacade2().getUserGroupsCategory().getCategories();
            Assert.assertEquals("Category attribute value is not stored", usergroups[0].getKey(), userGroup.getKey());
            clientFacade2.logout();
        }
        {
            Allocatable allocatable = getRaplaFacade1().getAllocatables()[0];
            Assert.assertEquals(4, allocatable.getClassification().getAttributes().length);
        }
        DynamicType typeEdit2 = getRaplaFacade1().edit(type);
        Attribute attributeLater = typeEdit2.getAttribute("test");
        Assert.assertTrue("Attributes identy changed after storing ", attributeLater.equals(newAttribute));
        typeEdit2.removeAttribute(attributeLater);
        getRaplaFacade1().store(typeEdit2);
        {
            Allocatable allocatable = getRaplaFacade1().getAllocatables()[0];
            Assert.assertEquals(getRaplaFacade1().getAllocatables().length, 5);
            Assert.assertEquals(3, allocatable.getClassification().getAttributes().length);
        }
        User user = getRaplaFacade1().newUser();
        user.setUsername("test-user");
        getRaplaFacade1().store(user);

        clientFacade2.login("homer", "duffs".toCharArray());
        removeAnAttribute();
        // Wait for the update
        {
            clientFacade2.login("homer", "duffs".toCharArray());
            getRaplaFacade2().getUser("test-user");
            clientFacade2.logout();
        }
    }

    public void removeAnAttribute() throws Exception
    {
        DynamicType typeEdit3 = getRaplaFacade1().edit(getRaplaFacade1().getDynamicType("room"));
        typeEdit3.removeAttribute(typeEdit3.getAttribute("belongsto"));
        Allocatable allocatable = getRaplaFacade1().getAllocatables()[0];
        Assert.assertEquals("erwin", allocatable.getName(locale));

        Allocatable allocatableClone = getRaplaFacade1().edit(allocatable);
        Assert.assertEquals(3, allocatable.getClassification().getAttributes().length);
        getRaplaFacade1().storeObjects(new Entity[] { allocatableClone, typeEdit3 });
        Assert.assertEquals(5, getRaplaFacade1().getAllocatables().length);
        final Attribute[] attributes = allocatable.getClassification().getAttributes();
        Assert.assertEquals(2, attributes.length);

        // we check if the store affectes the second client.
        getRaplaFacade2().refresh();
        Assert.assertEquals(5, getRaplaFacade2().getAllocatables().length);

        ClassificationFilter filter = getRaplaFacade2().getDynamicType("room").newClassificationFilter();
        filter.addIsRule("name", "erwin");
        {
            Allocatable rAfter = getRaplaFacade2().getAllocatables(filter.toArray())[0];
            final Attribute[] attributes1 = rAfter.getClassification().getAttributes();
            Assert.assertEquals(2, attributes1.length);
        }
        // facade2.getUserFromRequest("test-user");
        // Wait for the update
        clientFacade2.logout();
    }

    private Reservation findReservation(RaplaFacade facade, String typeKey, String name) throws RaplaException
    {
        DynamicType reservationType = facade.getDynamicType(typeKey);
        ClassificationFilter filter = reservationType.newClassificationFilter();
        filter.addRule("name", new Object[][] { { "contains", name } });
        Reservation[] reservations = facade.getReservationsForAllocatable(null, null, null, new ClassificationFilter[] { filter });
        if (reservations.length > 0)
            return reservations[0];
        else
            return null;
    }

    @Test
    public void testChangeDynamicType2() throws Exception
    {
        {
            DynamicType type = getRaplaFacade1().getDynamicTypes(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE)[0];
            DynamicType typeEdit3 = getRaplaFacade1().edit(type);
            typeEdit3.removeAttribute(typeEdit3.getAttribute("belongsto"));
            Allocatable[] allocatables = getRaplaFacade1().getAllocatables();
            Allocatable resource1 = allocatables[0];
            Assert.assertEquals("erwin", resource1.getName(locale));
            getRaplaFacade1().store(typeEdit3);
        }
        {
            Allocatable[] allocatables = getRaplaFacade1().getAllocatables();
            Allocatable resource1 = allocatables[0];
            Assert.assertEquals("erwin", resource1.getName(locale));
            Assert.assertEquals(2, resource1.getClassification().getAttributes().length);
        }
    }

    @Test
    public void testRemoveCategory() throws Exception
    {
        Category superCategoryClone = getRaplaFacade1().edit(getRaplaFacade1().getSuperCategory());
        Category department = superCategoryClone.getCategory("department");
        Category powerplant = department.getCategory("springfield-powerplant");
        powerplant.getParent().removeCategory(powerplant);
        try
        {
            getRaplaFacade1().store(superCategoryClone);
            Assert.fail("Dependency Exception should have been thrown");
        }
        catch (DependencyException ex)
        {
        }
    }

    @Test
    public void testChangeLogin() throws RaplaException
    {
        clientFacade2.logout();
        clientFacade2.login("monty", "burns".toCharArray());

        // boolean canChangePassword = facade2.canChangePassword();
        User user = clientFacade2.getUser();
        clientFacade2.changePassword(user, "burns".toCharArray(), "newPassword".toCharArray());
        clientFacade2.logout();
    }

    @Test
    public void testRemoveCategoryBug5() throws Exception
    {
        DynamicType type = getRaplaFacade1().newDynamicType(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE);
        String testTypeName = "TestType";
        type.getName().setName("en", testTypeName);
        Attribute att = getRaplaFacade1().newAttribute(AttributeType.CATEGORY);
        att.setKey("testdep");
        {
            Category superCategoryClone = getRaplaFacade1().getSuperCategory();
            Category department = superCategoryClone.getCategory("department");
            att.setConstraint(ConstraintIds.KEY_ROOT_CATEGORY, department);
        }
        type.addAttribute(att);
        getRaplaFacade1().store(type);
        Category superCategoryClone = getRaplaFacade1().edit(getRaplaFacade1().getSuperCategory());
        Category department = superCategoryClone.getCategory("department");
        superCategoryClone.removeCategory(department);
        try
        {
            getRaplaFacade1().store(superCategoryClone);
            Assert.fail("Dependency Exception should have been thrown");
        }
        catch (DependencyException ex)
        {
            Collection<String> dependencies = ex.getDependencies();
            Assert.assertTrue("Dependencies doesnt contain " + testTypeName, contains(dependencies, testTypeName));
        }

    }

    private boolean contains(Collection<String> dependencies, String testTypeName)
    {
        for (String dep : dependencies)
        {
            if (dep.contains(testTypeName))
            {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testStoreFilter() throws Exception
    {
        // select from event where name contains 'planting' or name contains
        // 'test';
        DynamicType dynamicType = getRaplaFacade1().getDynamicType("room");
        ClassificationFilter classificationFilter = dynamicType.newClassificationFilter();
        Category channel6 = getRaplaFacade1().getSuperCategory().getCategory("department").getCategory("channel-6");
        Category testdepartment = getRaplaFacade1().getSuperCategory().getCategory("department").getCategory("testdepartment");
        classificationFilter.setRule(0, dynamicType.getAttribute("belongsto"), new Object[][] { { "is", channel6 }, { "is", testdepartment } });
        boolean thrown = false;
        ClassificationFilter[] filter = new ClassificationFilter[] { classificationFilter };
        User user1 = clientFacade1.getUser();
        CalendarSelectionModel calendar = getRaplaFacade1().newCalendarModel(user1);
        calendar.setViewId(WeekviewPlugin.WEEK_VIEW);
        calendar.setAllocatableFilter(filter);
        calendar.setSelectedObjects(Collections.emptyList());
        calendar.setSelectedDate(getRaplaFacade1().today());
        CalendarModelConfiguration conf = ((CalendarModelImpl) calendar).createConfiguration();
        Preferences prefs = getRaplaFacade1().edit(getRaplaFacade1().getPreferences(clientFacade1.getUser()));
        TypedComponentRole<CalendarModelConfiguration> TEST_CONF = new TypedComponentRole<CalendarModelConfiguration>("org.rapla.TestEntry");
        prefs.putEntry(TEST_CONF, conf);
        getRaplaFacade1().store(prefs);

        RaplaFacade facade = getServerFacade();
        User user = facade.getUser("homer");
        Preferences storedPrefs = facade.getPreferences(user);
        Assert.assertNotNull(storedPrefs);
        CalendarModelConfiguration storedConf = storedPrefs.getEntry(TEST_CONF);
        Assert.assertNotNull(storedConf);

        ClassificationFilter[] storedFilter = storedConf.getFilter();
        Assert.assertEquals(1, storedFilter.length);
        ClassificationFilter storedClassFilter = storedFilter[0];

        Assert.assertEquals(1, storedClassFilter.ruleSize());

        try
        {
            Category parent = getRaplaFacade1().edit(testdepartment.getParent());
            parent.removeCategory(testdepartment);
            getRaplaFacade1().store(parent);
        }
        catch (DependencyException ex)
        {
            Assert.assertTrue(contains(ex.getDependencies(), prefs.getName(locale)));
            thrown = true;
        }
        Assert.assertTrue("Dependency Exception should have been thrown!", thrown);
    }

    @Test
    public void testCalendarStore() throws Exception
    {
        Date futureDate = new Date(getRaplaFacade1().today().getTime() + DateTools.MILLISECONDS_PER_WEEK * 10);
        Reservation r = getRaplaFacade1().newReservation();
        r.addAppointment(getRaplaFacade1().newAppointment(futureDate, futureDate));
        r.getClassification().setValue("name", "Test");

        getRaplaFacade1().store(r);

        CalendarSelectionModel calendar = getRaplaFacade1().newCalendarModel(clientFacade1.getUser());
        calendar.setViewId(WeekviewPlugin.WEEK_VIEW);
        calendar.setSelectedObjects(Collections.singletonList(r));
        calendar.setSelectedDate(getRaplaFacade1().today());
        calendar.setTitle("test");
        CalendarModelConfiguration conf = ((CalendarModelImpl) calendar).createConfiguration();
        {
            Preferences prefs = getRaplaFacade1().edit(getRaplaFacade1().getPreferences());
            TypedComponentRole<CalendarModelConfiguration> TEST_ENTRY = new TypedComponentRole<CalendarModelConfiguration>("org.rapla.test");
            prefs.putEntry(TEST_ENTRY, conf);
            getRaplaFacade1().store(prefs);
        }
    }

    @Test
    public void testReservationWithExceptionDoesntShow() throws Exception
    {
        {
            getRaplaFacade1().removeObjects(getRaplaFacade1().getReservationsForAllocatable(null, null, null, null));
        }
        Date start = new Date();
        Date end = new Date(start.getTime() + DateTools.MILLISECONDS_PER_HOUR * 2);
        {
            Reservation r = getRaplaFacade1().newReservation();
            r.getClassification().setValue("name", "test-reservation");
            Appointment a = getRaplaFacade1().newAppointment(start, end);
            a.setRepeatingEnabled(true);
            a.getRepeating().setType(Repeating.WEEKLY);
            a.getRepeating().setInterval(2);
            a.getRepeating().setNumber(10);
            r.addAllocatable(getRaplaFacade1().getAllocatables()[0]);
            r.addAppointment(a);
            a.getRepeating().addException(start);
            a.getRepeating().addException(new Date(start.getTime() + DateTools.MILLISECONDS_PER_WEEK));
            getRaplaFacade1().store(r);
            clientFacade1.logout();
        }
        {
            Reservation[] res = getRaplaFacade2()
                    .getReservationsForAllocatable(null, start, new Date(start.getTime() + 8 * DateTools.MILLISECONDS_PER_WEEK), null);
            Assert.assertEquals(1, res.length);
            Thread.sleep(100);
            clientFacade2.logout();
        }

    }

    @Test
    public void testChangeGroup() throws Exception
    {
        final PermissionController permissionController = DefaultPermissionControllerSupport.getController(getRaplaFacade1().getOperator());
        User user = getRaplaFacade1().edit(getRaplaFacade1().getUser("monty"));
        Category[] groups = user.getGroupList().toArray(new Category[] {});
        Assert.assertTrue("No groups found!", groups.length > 0);
        Category myGroup = getRaplaFacade1().getUserGroupsCategory().getCategory("my-group");
        Assert.assertTrue(Arrays.asList(groups).contains(myGroup));
        user.removeGroup(myGroup);
        Allocatable testResource = getRaplaFacade2().edit(getRaplaFacade2().getAllocatables()[0]);
        Assert.assertTrue(permissionController.canAllocate(testResource, getRaplaFacade2().getUser("monty"), null, null, null));
        testResource.removePermission(testResource.getPermissionList().iterator().next());
        Permission newPermission = testResource.newPermission();
        newPermission.setGroup(getRaplaFacade1().getUserGroupsCategory().getCategory("my-group"));
        newPermission.setAccessLevel(Permission.READ);
        testResource.addPermission(newPermission);
        Assert.assertFalse(permissionController.canAllocate(testResource, getRaplaFacade2().getUser("monty"), null, null, null));
        Assert.assertTrue(permissionController.canRead(testResource, getRaplaFacade2().getUser("monty")));
        getRaplaFacade1().store(user);
        getRaplaFacade2().refresh();
        Assert.assertFalse(permissionController.canAllocate(testResource, getRaplaFacade2().getUser("monty"), null, null, null));
    }

    public RaplaFacade getRaplaFacade1()
    {
        return clientFacade1.getRaplaFacade();
    }

    @Test
    public void testRemoveAppointment() throws Exception
    {
        Allocatable[] allocatables = getRaplaFacade1().getAllocatables();
        Date start = getRaplaLocale().toRaplaDate(2005, 11, 10);
        Date end = getRaplaLocale().toRaplaDate(2005, 11, 15);
        Reservation r = getRaplaFacade1().newReservation();
        r.getClassification().setValue("name", "newReservation");
        r.addAppointment(getRaplaFacade1().newAppointment(start, end));
        r.addAllocatable(allocatables[0]);
        ClassificationFilter f = r.getClassification().getType().newClassificationFilter();
        f.addEqualsRule("name", "newReservation");
        getRaplaFacade1().store(r);
        r = getRaplaFacade1().getPersistant(r);
        getRaplaFacade1().remove(r);
        Reservation[] allRes = getRaplaFacade1().getReservationsForAllocatable(null, null, null, new ClassificationFilter[] { f });
        Assert.assertEquals(0, allRes.length);
    }

    @Test
    public void testRestrictionsBug7() throws Exception
    {
        Reservation r = getRaplaFacade1().newReservation();
        r.getClassification().setValue("name", "newReservation");
        Appointment app1;
        {
            Date start = getRaplaLocale().toRaplaDate(2005, 11, 10);
            Date end = getRaplaLocale().toRaplaDate(2005, 10, 15);
            app1 = getRaplaFacade1().newAppointment(start, end);
            r.addAppointment(app1);
        }
        Appointment app2;
        {
            Date start = getRaplaLocale().toRaplaDate(2008, 11, 10);
            Date end = getRaplaLocale().toRaplaDate(2008, 11, 15);
            app2 = getRaplaFacade1().newAppointment(start, end);
            r.addAppointment(app2);
        }
        Allocatable allocatable = getRaplaFacade1().getAllocatables()[0];
        r.addAllocatable(allocatable);
        r.setRestriction(allocatable, new Appointment[] { app1, app2 });
        getRaplaFacade1().store(r);
        clientFacade1.logout();
        clientFacade1.login("homer", "duffs".toCharArray());
        ClassificationFilter f = r.getClassification().getType().newClassificationFilter();
        f.addEqualsRule("name", "newReservation");
        Reservation[] allRes = getRaplaFacade1().getReservationsForAllocatable(null, null, null, new ClassificationFilter[] { f });
        Reservation test = allRes[0];
        allocatable = getRaplaFacade1().getAllocatables()[0];
        Appointment[] restrictions = test.getRestriction(allocatable);
        Assert.assertEquals("Restrictions needs to be saved!", 2, restrictions.length);

    }

    @Test
    public void testMultilineTextField() throws Exception
    {

        String reservationName = "bowling";
        {
            RaplaFacade facade = getServerFacade();
            String description = getDescriptionOfReservation(facade, reservationName);
            Assert.assertTrue(description.contains("\n"));
        }
        {
            RaplaFacade facade = clientFacade1.getRaplaFacade();
            String description = getDescriptionOfReservation(facade, reservationName);
            Assert.assertTrue(description.contains("\n"));
        }
    }

    public String getDescriptionOfReservation(RaplaFacade facade, String reservationName) throws RaplaException
    {
        User user = null;
        Date start = null;
        Date end = null;
        ClassificationFilter filter = facade.getDynamicType("event").newClassificationFilter();
        filter.addEqualsRule("name", reservationName);
        Reservation[] reservations = facade.getReservations(user, start, end, filter.toArray());
        Reservation bowling = reservations[0];
        Classification classification = bowling.getClassification();
        Object descriptionValue = classification.getValue("description");
        String description = descriptionValue.toString();
        return description;
    }

    @Test
    public void testRefresh() throws Exception {
        changeInSecondFacade(clientFacade2,"bowling");
        getRaplaFacade1().refresh();
        Reservation resAfter = findReservation(clientFacade1.getRaplaFacade(),"bowling");
        Appointment appointment = resAfter.getAppointments()[0];
        Calendar cal = Calendar.getInstance(DateTools.getTimeZone());
        cal.setTime(appointment.getStart());
        Assert.assertEquals(17, cal.get(Calendar.HOUR_OF_DAY));
        Assert.assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK));
        cal.setTime(appointment.getEnd());
        Assert.assertEquals(19, cal.get(Calendar.HOUR_OF_DAY));
        Assert.assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK));
    }

    @Test
    public void testSavePreferences() throws Exception {
        clientFacade2.logout();
         Assert.assertTrue(clientFacade2.login("monty", "burns".toCharArray()));
        Preferences prefs = getRaplaFacade2().edit(getRaplaFacade2().getPreferences());
        getRaplaFacade2().store(prefs);
        clientFacade2.logout();
    }
    // Make some Changes to the Reservation in another client
    private void changeInSecondFacade(ClientFacade facade2,String name) throws Exception {
        UserModule userMod2 =  facade2;
        QueryModule queryMod2 =  facade2.getRaplaFacade();
        ModificationModule modificationMod2 =  facade2.getRaplaFacade();
        Reservation reservation = findReservation(queryMod2,name);
        Reservation mutableReseravation = modificationMod2.edit(reservation);
        Appointment appointment =  mutableReseravation.getAppointments()[0];

        RaplaLocale loc = getRaplaLocale();
        Calendar cal = loc.createCalendar();
        cal.set(Calendar.DAY_OF_WEEK,Calendar.MONDAY);
        Date startTime = loc.toTime( 17,0,0);
        Date startTime1 = loc.toDate(cal.getTime(), startTime);
        Date endTime = loc.toTime( 19,0,0);
        Date endTime1 = loc.toDate(cal.getTime(), endTime);
        appointment.move(startTime1,endTime1);

        modificationMod2.store( mutableReseravation );
        //userMod2.logout();
    }

    private Reservation findReservation(QueryModule queryMod,String name) throws RaplaException {
        Reservation[] reservations = queryMod.getReservationsForAllocatable(null,null,null,null);
        for (int i=0;i<reservations.length;i++) {
            if (reservations[i].getName(locale).equals(name))
                return reservations[i];
        }
        return null;
    }
}
