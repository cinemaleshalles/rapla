package org.rapla.client.menu.sandbox.gwt;

import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.user.client.Window;
import org.rapla.client.PopupContext;
import org.rapla.client.gwt.GwtPopupContext;
import org.rapla.client.menu.sandbox.data.Point;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ContextCreator
{
    @Inject
    public ContextCreator()
    {

    }
    public PopupContext createContext(DomEvent<?> event)
    {
        final NativeEvent nativeEvent = event.getNativeEvent();
        final int clientX = nativeEvent.getClientX();
        final int scrollLeft = Window.getScrollLeft();
        final int clientY = nativeEvent.getClientY();
        final int scrollTop = Window.getScrollTop();
        final int x = clientX + scrollLeft;
        final int y = clientY + scrollTop;
        final Point point = new Point(x, y);
        return new GwtPopupContext(point);
    }
}
