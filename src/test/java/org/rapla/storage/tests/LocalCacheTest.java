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
package org.rapla.storage.tests;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.rapla.components.util.DateTools;
import org.rapla.entities.Entity;
import org.rapla.entities.User;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.Period;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.domain.internal.AllocatableImpl;
import org.rapla.entities.dynamictype.Attribute;
import org.rapla.entities.dynamictype.AttributeType;
import org.rapla.entities.dynamictype.Classification;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.DynamicTypeAnnotations;
import org.rapla.entities.dynamictype.internal.AttributeImpl;
import org.rapla.entities.dynamictype.internal.DynamicTypeImpl;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.RaplaFacade;
import org.rapla.framework.RaplaException;
import org.rapla.storage.CachableStorageOperator;
import org.rapla.storage.CachableStorageOperatorCommand;
import org.rapla.storage.LocalCache;
import org.rapla.storage.PermissionController;
import org.rapla.storage.StorageOperator;
import org.rapla.test.util.DefaultPermissionControllerSupport;
import org.rapla.test.util.RaplaTestCase;

import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

@RunWith(JUnit4.class)
public class LocalCacheTest  {
    Locale locale;


    @Before
    public void setUp() throws Exception {
        locale = Locale.getDefault();
    }

    public DynamicTypeImpl createDynamicType() throws Exception {
        AttributeImpl attribute = new AttributeImpl(AttributeType.STRING);
        attribute.setKey("name");
        attribute.setId(getId(Attribute.class,1));
        DynamicTypeImpl dynamicType = new DynamicTypeImpl();
        dynamicType.setKey("defaultResource");
        dynamicType.setId(getId(DynamicType.class,1));
        dynamicType.addAttribute(attribute);
        dynamicType.setAnnotation(DynamicTypeAnnotations.KEY_NAME_FORMAT,"{name}");
        return dynamicType;
    }


    public AllocatableImpl createResource(LocalCache cache,int intId,DynamicType type,String name) {
        Date today = new Date();
        AllocatableImpl resource = new AllocatableImpl(today, today);
        resource.setId(getId(Allocatable.class,intId));
        resource.setResolver( cache);
        Classification classification = type.newClassification();
        classification.setValue("name",name);
        resource.setClassification(classification);
        return resource;
    }

    private String getId(Class<? extends Entity> type, int intId) {
        return type.toString() + "_" + intId;
    }

    @Test
    public void testAllocatable() throws Exception {
        StorageOperator operator = null;
        final PermissionController controller = DefaultPermissionControllerSupport.getController(operator);
        LocalCache cache = new LocalCache(controller);

        DynamicTypeImpl type = createDynamicType();
        type.setResolver( cache);
        type.setReadOnly(  );
        cache.put( type );
        AllocatableImpl resource1 = createResource(cache,1,type,"Adrian");
        cache.put(resource1);
        AllocatableImpl resource2 = createResource(cache,2,type,"Beta");
        cache.put(resource2);
        AllocatableImpl resource3 = createResource(cache,3,type,"Ceta");
        cache.put(resource3);

        resource1.getClassification().setValue("name","Zeta");
        cache.put(resource1);
        Allocatable[] resources = cache.getAllocatables().toArray(Allocatable.ALLOCATABLE_ARRAY);
        Assert.assertEquals(3, resources.length);
        Assert.assertTrue(resources[1].getName(locale).equals("Beta"));
    }

    @Test
    public void test2() throws Exception {

        final ClientFacade clientFacade = RaplaTestCase.createSimpleSimpsonsWithHomer();
        RaplaFacade facade = clientFacade.getRaplaFacade();
        final CachableStorageOperator storage = (CachableStorageOperator) facade.getOperator();
        final Period[] periods = facade.getPeriods();
        storage.runWithReadLock(new CachableStorageOperatorCommand() {
			
			@Override
			public void execute(LocalCache cache) throws RaplaException {
			    try
				{
		            ClassificationFilter[] filters = null;
		            Map<String, String> annotationQuery = null;
		            {
		                final Period period = periods[2];
		                Collection<Reservation> reservations = storage.getReservations(null,null,period.getStart(),period.getEnd(),filters,annotationQuery).get();
                        Assert.assertEquals(0, reservations.size());
		            }
		            {
		                final Period period = periods[1];
	                    Collection<Reservation> reservations = storage.getReservations(null,null,period.getStart(),period.getEnd(), filters,annotationQuery).get();
                        Assert.assertEquals(2, reservations.size());
		            }
		            {
    		            User user = cache.getUser("homer");
    		            Collection<Reservation> reservations = storage.getReservations(user,null,null,null, filters,annotationQuery).get();
                        Assert.assertEquals(3, reservations.size());
		            }
		            {
		                User user = cache.getUser("homer");
		                final Period period = periods[1];
                        Collection<Reservation> reservations = storage.getReservations(user,null,period.getStart(),period.getEnd(),filters, annotationQuery).get();
                        Assert.assertEquals(2, reservations.size());
		            }
		        }
			    catch (Exception ex)
			    {
			        throw new RaplaException(ex.getMessage(),ex);
			    }
		        {
		            for (Allocatable next:cache.getAllocatables())
		            {
		                if ( ((DynamicTypeImpl)next.getClassification().getType()).isInternal())
		                {
		                    continue;
		                }
                        Assert.assertEquals("erwin", next.getName(locale));
		                break;
		            }
		        }		
			}
		});
       
    }

    @Test
    public void testConflicts() throws Exception {
        final ClientFacade clientFacade = RaplaTestCase.createSimpleSimpsonsWithHomer();
        RaplaFacade facade = clientFacade.getRaplaFacade();
        CachableStorageOperator storage = (CachableStorageOperator) facade.getOperator();
        Reservation reservation = facade.newReservation();
        //start is 13/4  original end = 28/4
        Date startDate = new Date(DateTools.toDate(2013, 4, 13));
        Date endDate = new Date(DateTools.toDate(2013, 4, 28));
        Appointment appointment = facade.newAppointment(startDate, endDate);
        reservation.addAppointment(appointment);
        reservation.getClassification().setValue("name", "test");
        facade.store( reservation);
        
        Reservation modifiableReservation = facade.edit(reservation);

        
        Date splitTime = new Date(DateTools.toDate(2013, 4, 20));
        Appointment modifiableAppointment = modifiableReservation.findAppointment( appointment);
       // left part
        //leftpart.move(13/4, 20/4)
        modifiableAppointment.move(appointment.getStart(), splitTime);

        facade.store( modifiableReservation);
      
        User user = null;
		Collection<Allocatable> allocatables = null;
		Map<String, String> annotationQuery = null;
		ClassificationFilter[] filters = null;
		Collection<Reservation> reservations = storage.getReservations(user, allocatables, startDate, endDate, filters,annotationQuery).get();
        Assert.assertEquals(1, reservations.size());
    }
}





