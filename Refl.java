package net.joeyscobblemon.gts;

import java.lang.reflect.Method;

/** Tiny reflection helper so the mod never needs Cobblemon on its compile classpath. */
public final class Refl {
    private Refl() {}

    public static Object call(Object target, String name, Object... args) throws Exception {
        Method m = find(target.getClass(), name, args);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "#" + name + "/" + args.length);
        try { m.setAccessible(true); } catch (Throwable ignored) {}
        return m.invoke(target, args);
    }

    public static Object tryCall(Object target, String name, Object... args) {
        try { return call(target, name, args); } catch (Throwable t) { return null; }
    }

    private static Method find(Class<?> c, String name, Object[] args) {
        for (Method m : c.getMethods()) if (match(m, name, args)) return m;
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) if (match(m, name, args)) return m;
        return null;
    }

    private static boolean match(Method m, String name, Object[] args) {
        if (!m.getName().equals(name) || m.getParameterCount() != args.length) return false;
        Class<?>[] pt = m.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (args[i] == null) { if (pt[i].isPrimitive()) return false; continue; }
            if (!box(pt[i]).isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }
}
