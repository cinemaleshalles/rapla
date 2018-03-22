package org.rapla.client.internal.check.gwt;

import jsinterop.annotations.JsType;
import org.rapla.client.dialog.DialogInterface;
import org.rapla.components.i18n.I18nIcon;
import org.rapla.scheduler.Promise;
import org.rapla.scheduler.UnsynchronizedPromise;

@JsType
public class VueDialog implements DialogInterface {

  private String icon;
  private String title;
  private Object content;
  private Promise<Integer> promise= new UnsynchronizedPromise<>();

  public VueDialog(final Object content) {
    this.content = content;
  }

  @Override
  public Promise<Integer> start(final boolean pack) {
    RaplaVue.emit("gwt-dialog-open", this);
    return promise;
  }

  public Object getContent() {
    return content;
  }

  public Promise<Integer> getPromise() {
    return promise;
  }

  public String getTitle() {
    return title;
  }

  public String getIcon() {
    return icon;
  }

  @Override
  public void busy(final String message) {

  }

  @Override
  public void idle() {

  }

  @Override
  public void setTitle(final String title) {
    this.title = title;
  }

  @Override
  public void setIcon(final I18nIcon iconKey) {
    this.icon = iconKey.getId();
  }

  @Override
  public void close() {

  }

  /**
   * buttons
   */
  @Override
  public DialogAction getAction(final int commandIndex) {
    return new DialogAction() {
      @Override
      public void setEnabled(final boolean enabled) {

      }

      @Override
      public void setRunnable(final Runnable runnable) {
        throw new UnsupportedOperationException("does not work in GWT-Mode");
      }

      @Override
      public void setIcon(final I18nIcon icon) {

      }

      @Override
      public void execute() {
        throw new UnsupportedOperationException("does not work in GWT-Mode");
      }
    };
  }

  @Override
  public void setAbortAction(final Runnable abortAction) {

  }

  @Override
  public void setDefault(final int commandIndex) {

  }
}
