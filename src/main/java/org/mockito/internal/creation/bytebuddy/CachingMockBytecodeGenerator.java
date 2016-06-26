package org.mockito.internal.creation.bytebuddy;

import static org.mockito.internal.util.StringJoiner.join;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.mockito.exceptions.base.MockitoException;

class CachingMockBytecodeGenerator extends ReferenceQueue<ClassLoader> {

    private static final ClassLoader BOOT_LOADER = new URLClassLoader(new URL[0], null);

    final ConcurrentMap<Value<ClassLoader>, CachedBytecodeGenerator> avoidingClassLeakageCache =
            new ConcurrentHashMap<Value<ClassLoader>, CachedBytecodeGenerator>();

    private final MockBytecodeGenerator mockBytecodeGenerator = new MockBytecodeGenerator();

    @SuppressWarnings("unchecked")
    public <T> Class<T> get(MockFeatures<T> params) {
        pollAndClearReferences();
        return (Class<T>) mockCachePerClassLoaderOf(params.mockedType.getClassLoader()).getOrGenerateMockClass(params);
    }

    void pollAndClearReferences() {
        Reference<?> reference;
        while ((reference = poll()) != null) {
            avoidingClassLeakageCache.remove(reference);
        }
    }

    private CachedBytecodeGenerator mockCachePerClassLoaderOf(ClassLoader classLoader) {
        classLoader = classLoader == null ? BOOT_LOADER : classLoader;
        CachedBytecodeGenerator generator = avoidingClassLeakageCache.get(new Key<ClassLoader>(classLoader)), newGenerator;
        if (generator == null) {
            generator = avoidingClassLeakageCache.putIfAbsent(new WeakKey<ClassLoader>(classLoader, this), newGenerator = new CachedBytecodeGenerator(mockBytecodeGenerator));
            if (generator == null) {
                generator = newGenerator;
            }
        }
        return generator;
    }

    private static class CachedBytecodeGenerator {

        private ConcurrentHashMap<MockKey, WeakReference<Class<?>>> generatedClassCache =
                new ConcurrentHashMap<MockKey, WeakReference<Class<?>>>();
        private final MockBytecodeGenerator generator;

        private CachedBytecodeGenerator(MockBytecodeGenerator generator) {
            this.generator = generator;
        }

        public <T> Class<?> getOrGenerateMockClass(MockFeatures<T> features) {
            MockKey<?> mockKey = MockKey.of(features.mockedType, features.interfaces);
            Class<?> generatedMockClass = null;
            WeakReference<Class<?>> classWeakReference = generatedClassCache.get(mockKey);
            if(classWeakReference != null) {
                generatedMockClass = classWeakReference.get();
            }
            if(generatedMockClass == null) {
                generatedMockClass = generate(features);
            }
            generatedClassCache.put(mockKey, new WeakReference<Class<?>>(generatedMockClass));
            return generatedMockClass;
        }

        private <T> Class<? extends T> generate(MockFeatures<T> mockFeatures) {
            try {
                return generator.generateMockClass(mockFeatures);
            } catch (Exception bytecodeGenerationFailed) {
                throw prettifyFailure(mockFeatures, bytecodeGenerationFailed);
            }
        }

        private RuntimeException prettifyFailure(MockFeatures<?> mockFeatures, Exception generationFailed) {
            if (Modifier.isPrivate(mockFeatures.mockedType.getModifiers())) {
                throw new MockitoException(join(
                        "Mockito cannot mock this class: " + mockFeatures.mockedType + ".",
                        "Most likely it is a private class that is not visible by Mockito",
                        ""
                ), generationFailed);
            }
            throw new MockitoException(join(
                    "Mockito cannot mock this class: " + mockFeatures.mockedType,
                    "",
                    "Mockito can only mock visible & non-final classes.",
                    "If you're not sure why you're getting this error, please report to the mailing list.",
                    "",
                    "Underlying exception : " + generationFailed),
                    generationFailed
            );
        }

        // should be stored as a weak reference
        private static class MockKey<T> {
            private final String mockedType;
            private final Set<String> types = new HashSet<String>();

            private MockKey(Class<T> mockedType, Set<Class<?>> interfaces) {
                this.mockedType = mockedType.getName();
                for (Class<?> anInterface : interfaces) {
                    types.add(anInterface.getName());
                }
                types.add(this.mockedType);
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;

                MockKey mockKey = (MockKey<?>) other;

                if (!mockedType.equals(mockKey.mockedType)) return false;
                if (!types.equals(mockKey.types)) return false;

                return true;
            }

            @Override
            public int hashCode() {
                int result = mockedType.hashCode();
                result = 31 * result + types.hashCode();
                return result;
            }

            public static <T> MockKey<T> of(Class<T> mockedType, Set<Class<?>> interfaces) {
                return new MockKey<T>(mockedType, interfaces);
            }
        }
    }

    private interface Value<T> {

        T get();
    }

    private class Key<T> implements Value<T> {

        private final T value;

        private final int hashCode;

        public Key(T value) {
            this.value = value;
            hashCode = System.identityHashCode(value);
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Value)) return false;
            return value == ((Value<?>) object).get();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private class WeakKey<T> extends WeakReference<T> implements Value<T> {

        private final int hashCode;

        public WeakKey(T referent, ReferenceQueue<? super T> q) {
            super(referent, q);
            hashCode = System.identityHashCode(referent);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Value)) return false;
            return get() == ((Value<?>) object).get();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
