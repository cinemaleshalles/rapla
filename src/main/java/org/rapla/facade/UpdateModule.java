/*--------------------------------------------------------------------------*
 | Copyright (C) 2013 Gereon Fassbender, Christopher Kohlhaas               |
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
package org.rapla.facade;

import org.rapla.components.util.undo.CommandHistory;
import org.rapla.entities.domain.Allocatable;
import org.rapla.framework.RaplaException;
import org.rapla.framework.TypedComponentRole;
public interface UpdateModule
{
	TypedComponentRole<Integer> REFRESH_INTERVAL_ENTRY = new TypedComponentRole<Integer>("org.rapla.refreshInterval");
    TypedComponentRole<Integer> ARCHIVE_AGE = new TypedComponentRole<Integer>("org.rapla.archiveAge");
	int REFRESH_INTERVAL_DEFAULT = 30000;

    /**
     *  registers a new ModificationListener.
     *  A ModifictionEvent will be fired to every registered DateChangeListener
     *  when one or more entities have been added, removed or changed
     * @see ModificationListener
     * @see ModificationEvent
    */
    void addModificationListener(ModificationListener listener);
    void removeModificationListener(ModificationListener listener);
    void addUpdateErrorListener(UpdateErrorListener listener);
    void removeUpdateErrorListener(UpdateErrorListener listener);
    
    //void addAllocationChangedListener(AllocationChangeListener triggerListener);
    //void removeAllocationChangedListener(AllocationChangeListener triggerListener);

    void setTemplate(Allocatable template);
    Allocatable getTemplate();
    CommandHistory getCommandHistory();
}





