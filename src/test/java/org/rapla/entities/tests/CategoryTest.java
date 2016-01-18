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
import org.rapla.entities.Category;
import org.rapla.entities.DependencyException;
import org.rapla.entities.internal.CategoryImpl;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.ModificationModule;
import org.rapla.facade.QueryModule;
import org.rapla.facade.UpdateModule;
import org.rapla.framework.RaplaException;
import org.rapla.test.util.RaplaTestCase;

@RunWith(JUnit4.class)
public class CategoryTest {
    CategoryImpl areas;
    ModificationModule modificationMod;
    QueryModule queryMod;
    UpdateModule updateMod;


    @Before
    public void setUp() throws Exception {
        ClientFacade facade = RaplaTestCase.createSimpleSimpsonsWithHomer();
        queryMod = facade.getRaplaFacade();
        modificationMod = facade.getRaplaFacade();
        updateMod = facade;

        areas = (CategoryImpl) modificationMod.newCategory();
        areas.setKey("areas");
        areas.getName().setName("en","areas");
        Category area51 =  modificationMod.newCategory();
        area51.setKey("51");
        area51.getName().setName("en","area 51");
        Category buildingA =  modificationMod.newCategory();
        buildingA.setKey("A");
        buildingA.getName().setName("en","building A");
        Category floor1 =  modificationMod.newCategory();
        floor1.setKey("1");
        floor1.getName().setName("en","floor 1");

        buildingA.addCategory(floor1);
        area51.addCategory(buildingA);
        areas.addCategory(area51);
    }

    @Test
    public void testStore2() throws Exception {
        Category superCategory = modificationMod.edit(queryMod.getSuperCategory());
        
        superCategory.addCategory(areas);
        modificationMod.store(superCategory);
        Assert.assertTrue(areas.getId() != null);
        Category editObject =  modificationMod.edit(superCategory);
        modificationMod.store(editObject);
        Assert.assertTrue("reference to subcategory has changed", areas == superCategory.getCategory("areas"));
    }

    @Test
    public void testStore() throws Exception {
        Category superCategory =modificationMod.edit(queryMod.getSuperCategory());
        superCategory.addCategory(areas);
        modificationMod.store(superCategory);
        Assert.assertTrue(areas.getId() != null);
        updateMod.refresh();
        Category[] categories = queryMod.getSuperCategory().getCategories();
        for (int i=0;i<categories.length;i++)
            if (categories[i].equals(areas))
                return;
        Assert.assertTrue("category not stored!", false);
    }

    @Test
    public void testStore3() throws Exception {
        Category superCategory = queryMod.getSuperCategory();
        Category department = modificationMod.edit( superCategory.getCategory("department") );
        Category school = department.getCategory("elementary-springfield");
        try {
            department.removeCategory( school);
            modificationMod.store( department );
            Assert.fail("No dependency exception thrown");
        } catch (DependencyException ex) {
        }
        school = modificationMod.edit( superCategory.getCategory("department").getCategory("channel-6") );
        modificationMod.store( school );
    }

    @Test
    public void testEditDeleted() throws Exception {
        Category superCategory = queryMod.getSuperCategory();
        Category department = modificationMod.edit( superCategory.getCategory("department") );
        Category subDepartment = department.getCategory("testdepartment");
        department.removeCategory( subDepartment);
        modificationMod.store( department );
        try {
           Category subDepartmentEdit = modificationMod.edit( subDepartment );
           modificationMod.store( subDepartmentEdit );
            Assert.fail("store should throw an exception, when trying to edit a removed entity ");
        } catch ( RaplaException ex) {
        }
    }


    @Test
    public void testGetParent() {
        Category area51 = areas.getCategory("51");
        Category buildingA = area51.getCategory("A");
        Category floor1 = buildingA.getCategories()[0];
        Assert.assertEquals(areas, area51.getParent());
        Assert.assertEquals(area51, buildingA.getParent());
        Assert.assertEquals(buildingA, floor1.getParent());
    }

    @Test
    @SuppressWarnings("null")
    public void testPath() throws Exception {
        String path = "category[key='51']/category[key='A']/category[key='1']";
        Category sub =areas.getCategoryFromPath(path);
        Assert.assertTrue(sub != null);
        Assert.assertTrue(sub.getName().getName("en").equals("floor 1"));
        String path2 = areas.getPathForCategory(sub);
        //      System.out.println(path2);
        Assert.assertEquals(path, path2);
    }

    @Test
    public void testAncestorOf() throws Exception {
        String path = "category[key='51']/category[key='A']/category[key='1']";
        Category sub =areas.getCategoryFromPath(path);
        Assert.assertTrue(areas.isAncestorOf(sub));
        Assert.assertTrue(!sub.isAncestorOf(areas));
    }
}





