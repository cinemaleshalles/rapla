package org.rapla.client.extensionpoints;

import org.rapla.gui.toolkit.IdentifiableMenuEntry;
import org.rapla.inject.ExtensionPoint;
import org.rapla.inject.InjectionContext;

@ExtensionPoint(context = InjectionContext.swing,id="org.rapla.gui.EditMenuInsert")
public interface EditMenuExtension extends IdentifiableMenuEntry
{
}