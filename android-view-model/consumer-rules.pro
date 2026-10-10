# Legacy KClass declarations inspect the VM's generic parameters and enclosing class.
# Retain type metadata for raw-type validation; unused VM classes can still shrink.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep,allowshrinking,allowoptimization,allowobfuscation class milu.viewmodel.ViewModel

# A member class can record its direct enclosing instance in this$0. Including that
# descriptor preserves the enclosing metadata when the field remains live.
-keep,allowshrinking,allowoptimization,allowobfuscation,includedescriptorclasses class * extends milu.viewmodel.ViewModel {
    *** this$0;
}

# Recent javac versions can omit an unused this$0 field. Preserve live VM constructor
# signatures and their descriptor classes as well, so the implicit enclosing parameter
# remains a reliable endpoint even when it has no runtime use. This does not keep unused
# VM classes or unrelated application classes; the extra classes are VM parameter types.
# Only the direct enclosing class is needed: raw inner classes require an explicit KType.
-keepclassmembers,allowobfuscation,includedescriptorclasses class * extends milu.viewmodel.ViewModel {
    <init>(...);
}
