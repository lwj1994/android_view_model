package milu.viewmodel;

/** Minimal Java-only shrinker input; it does not link the Android runtime. */
public final class MetadataSmoke {
    public static void main(String[] args) {
        Class<?>[] types = {
            GenericModel.class,
            PlainModel.class,
            GenericOuter.Inner.class,
            GenericOuter.Middle.Deep.class,
            PlainOuter.Inner.class,
            GenericOuter.StaticModel.class,
            GenericOuter.StaticGenericModel.class,
            PlainOuter.NestedModel.class,
        };
        for (Class<?> type : types) {
            // Pass the Class object to opaque library code: getName() could be
            // constant-folded and make the corresponding class itself dead.
            System.out.println(type);
        }
    }
}

// This exact name makes the production consumer rules match the fixture.
class ViewModel {}

class GenericModel<T> extends ViewModel {}

class PlainModel extends ViewModel {}

class GenericOuter<T> {
    class Inner extends ViewModel {
        Object owner() { return GenericOuter.this; }
    }

    class Middle {
        class Deep extends ViewModel {
            Object owner() { return Middle.this; }
        }
    }

    static class StaticModel extends ViewModel {}

    static class StaticGenericModel<U> extends ViewModel {}
}

class PlainOuter {
    // Also cover a source-level inner class that never reads its enclosing object.
    class Inner extends ViewModel {}

    static class NestedModel extends ViewModel {}
}
