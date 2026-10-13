package milu.viewmodel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** Exercises the ABI used by Kotlin clients compiled with the old JVM-default mode. */
@SuppressWarnings("deprecation")
public class LegacyDefaultMethodsTest {
    @Before public void setUp() { ViewModel.Companion.reset(); }
    @After public void tearDown() { ViewModel.Companion.reset(); }

    @Test public void factoryCompatibilityBridgesRetainDefaults() {
        ViewModelBinding owner = new ViewModelBinding();
        try {
            ViewModelSpec<ViewModel> spec = new ViewModelSpec<>(
                kotlin.jvm.JvmClassMappingKt.getKotlinClass(ViewModel.class),
                null, null, false, ViewModel::new
            );
            assertNull(ViewModelFactory.DefaultImpls.key(spec));
            assertNull(ViewModelFactory.DefaultImpls.tag(spec));
            assertFalse(ViewModelFactory.DefaultImpls.aliveForever(spec));
            assertNull(TypedViewModelFactory.DefaultImpls.key(spec));
            assertNull(TypedViewModelFactory.DefaultImpls.tag(spec));
            assertFalse(TypedViewModelFactory.DefaultImpls.aliveForever(spec));
            ViewModel vm = owner.read(spec);
            ViewModelLifecycle lifecycle = new ViewModelLifecycle() {};
            ViewModelLifecycle.DefaultImpls.onCreate(lifecycle, vm, vm.getInstanceArg());
            ViewModelLifecycle.DefaultImpls.onBind(lifecycle, vm, vm.getInstanceArg(), owner.getId());
            ViewModelLifecycle.DefaultImpls.onUnbind(lifecycle, vm, vm.getInstanceArg(), owner.getId());
            ViewModelLifecycle.DefaultImpls.onDispose(lifecycle, vm, vm.getInstanceArg());
            assertFalse(vm.isDisposed());
            assertEquals(1, vm.getRefHandler().getOwners().size());
        } finally { owner.dispose(); }
    }
}
