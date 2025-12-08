package java.lang.ref;

import elemental2.core.WeakRef;

public abstract class Reference<T> {

    private WeakRef<T> jsWeakRef;

    Reference(T referent) {
        this(referent, (ReferenceQueue) null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        if (referent != null) {
            jsWeakRef = new WeakRef<>(referent);
        }
    }

    public T get() {
        if (jsWeakRef == null) {
            return null;
        }
        return jsWeakRef.deref();
    }

    public void clear() {
        if (jsWeakRef != null) {
            jsWeakRef = null;
        }
    }

    public boolean isEnqueued() {
        return false;
    }

    public boolean enqueue() {
        throw new IllegalStateException("never called when emulated");
    }

    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

}
