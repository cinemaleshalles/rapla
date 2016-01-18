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
package org.rapla.entities.tests;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.rapla.entities.configuration.CalendarModelConfiguration;
import org.rapla.entities.configuration.Preferences;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.dynamictype.Attribute;
import org.rapla.entities.dynamictype.AttributeType;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.ClassificationFilterRule;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.facade.CalendarSelectionModel;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.RaplaFacade;
import org.rapla.facade.ModificationModule;
import org.rapla.facade.QueryModule;
import org.rapla.facade.UpdateModule;
import org.rapla.facade.internal.CalendarModelImpl;
import org.rapla.framework.TypedComponentRole;
import org.rapla.plugin.weekview.WeekviewPlugin;
import org.rapla.test.util.RaplaTestCase;

import java.util.Collections;
import java.util.Iterator;

@RunWith(JUnit4.class)
public class ClassificationFilterTest  {
    ClientFacade facade;
    ModificationModule modificationMod;
    QueryModule queryMod;
    UpdateModule updateMod;

    @Before
    public void setUp() throws Exception 
    {
        facade = RaplaTestCase.createSimpleSimpsonsWithHomer();
        queryMod = facade.getRaplaFacade();
        modificationMod = facade.getRaplaFacade();
        updateMod = facade;
    }

    @Test
    public void testStore() throws Exception {
        // select from event where (name contains 'planting' or name contains 'owl') or (description contains 'friends');
        DynamicType dynamicType = queryMod.getDynamicType("event");
        ClassificationFilter classificationFilter = dynamicType.newClassificationFilter();
        classificationFilter.setRule(0
                                     ,dynamicType.getAttribute("name")
                                     ,new Object[][] {
                                         {"contains","planting"}
                                         ,{"contains","owl"}
                                     }
                                     );
        classificationFilter.setRule(1
                                     ,dynamicType.getAttribute("description")
                                     ,new Object[][] {
                                         {"contains","friends"}
                                     }
                                     );
        /*
        modificationMod.newRaplaCalendarModel( )
        ReservationFilter filter  = modificationMod.newReservationFilter(, null, ReservationFilter.PARTICULAR_PERIOD, queryMod.getPeriods()[1], null, null );
        //  filter.setPeriod();
        //assertEquals("bowling",queryMod.getReservations(filter)[0].getClassification().getValue("name"));
        assertTrue(((EntityReferencer)filter).isRefering((RefEntity)dynamicType));
*/
        ClassificationFilter[] filter = new ClassificationFilter[] {classificationFilter};

        CalendarSelectionModel calendar = modificationMod.newCalendarModel(facade.getUser());
        calendar.setViewId( WeekviewPlugin.WEEK_VIEW);
        calendar.setSelectedObjects( Collections.emptyList());
        calendar.setSelectedDate( queryMod.today());
        calendar.setReservationFilter( filter);
        CalendarModelConfiguration conf = ((CalendarModelImpl)calendar).createConfiguration();
        Preferences prefs =  modificationMod.edit( queryMod.getPreferences());
        TypedComponentRole<CalendarModelConfiguration> testConf = new TypedComponentRole<CalendarModelConfiguration>("org.rapla.TestConf");
        prefs.putEntry( testConf, conf);
        modificationMod.store( prefs );

        DynamicType newDynamicType = modificationMod.edit( dynamicType );
        newDynamicType.removeAttribute(newDynamicType.getAttribute("description"));
        modificationMod.store( newDynamicType );

        CalendarModelConfiguration configuration = queryMod.getPreferences().getEntry(testConf);
        filter =  configuration.getFilter();
        Iterator<? extends ClassificationFilterRule> it = filter[0].ruleIterator();
        it.next();
        Assert.assertTrue("second rule should be removed.", !it.hasNext());

    }


    @Test
    public void testFilter() throws Exception {
        // Test if the new date attribute is used correctly in filters
        {
        	DynamicType dynamicType = queryMod.getDynamicType("room");
            DynamicType modifiableType = modificationMod.edit(dynamicType);
            Attribute attribute = modificationMod.newAttribute( AttributeType.DATE);
            attribute.setKey( "date");
            modifiableType.addAttribute(attribute);
            modificationMod.store( modifiableType);
        }
    	DynamicType dynamicType = queryMod.getDynamicType("room");
        //Issue 235 in rapla: Null date check in filter not working anymore
        ClassificationFilter classificationFilter = dynamicType.newClassificationFilter();
        Allocatable[] allocatablesWithoutFilter = queryMod.getAllocatables( classificationFilter.toArray());
        Assert.assertTrue(allocatablesWithoutFilter.length > 0);
        classificationFilter.setRule(0
                                     ,dynamicType.getAttribute("date")
                                     ,new Object[][] {
                                         {"=",null}
                                     }
                                     );
        Allocatable[] allocatables = queryMod.getAllocatables( classificationFilter.toArray());

        Assert.assertTrue(allocatables.length > 0);
    }

}
