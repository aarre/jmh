/**
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.util;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class for loading classes in various ways.
 *
 * @author anders.astrand@oracle.com
 */
public class ClassUtils {
    // Static access only
    private ClassUtils() {

    }

    /**
     * Helper method for adding a URL to the given URLClassLoader. Uses reflection and Method.setAccessible.
     *
     * @param classLoader URLClassLoader to add a URL to
     * @param urls        URLs to add
     * @throws NoSuchMethodException
     * @throws SecurityException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws IllegalArgumentException
     */
    private static void addURLsToClassLoader(URLClassLoader classLoader, URL... urls) throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        // addURL is a protected method, but we can use reflection to call it
        Class<?>[] paraTypes = new Class[1];
        paraTypes[0] = URL.class;
        Method method;

        method = URLClassLoader.class.getDeclaredMethod("addURL", paraTypes);

        // change access to true, otherwise, it will throw exception
        method.setAccessible(true);

        for (URL url : urls) {
            method.invoke(classLoader, url);
        }

    }

    /**
     * Enumerates all methods in hierarchy. Note that is different from both Class.getDeclaredMethods() and
     * Class.getMethods().
     *
     * @param clazz class to enumerate.
     * @return list of methods.
     */
    public static List<Method> enumerateMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<Method>();
        Class<?> current = clazz;
        while (current != null) {
            result.addAll(Arrays.asList(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return result;
    }

    public static Class<?> loadClass(String className) {
        // load the class in a different classloader
        try {
            String classPathValue = System.getProperty("java.class.path");
            String[] classPath = classPathValue.split(File.pathSeparator);
            URL[] classPathUrl = new URL[classPath.length];
            for (int i = 0; i < classPathUrl.length; i++) {
                try {
                    classPathUrl[i] = new File(classPath[i]).toURI().toURL();
                } catch (MalformedURLException ex) {
                    throw new RuntimeException("Error parsing the value of property java.class.path: " + classPathValue, ex);
                }
            }

            URLClassLoader loader = new URLClassLoader(classPathUrl);
            return loader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("MicroBenchmark does not match a class", ex);
        }
    }

    /**
     * Make the collection of class names denser.
     *
     * @param src source class names
     * @return map of <src class name, denser class name>
     */
    public static Map<String, String> denseClassNames(Collection<String> src) {
        if (src.isEmpty()) return Collections.emptyMap();

        int maxLen = Integer.MIN_VALUE;
        for (String s : src) {
            maxLen = Math.max(maxLen, s.length());
        }

        String[] prefix = null;
        for (String s : src) {
            String[] names = s.split("\\.");
            if (prefix == null) {
                prefix = names;
                continue;
            }

            int prefixRun = 0;
            for (int c = 0; c < Math.min(prefix.length, names.length); c++, prefixRun++) {
                if (!names[c].equals(prefix[c])) {
                    break;
                }
            }

            prefix = Arrays.copyOf(prefix, prefixRun);
        }

        for (int c = 0; c < prefix.length; c++) {
            if (prefix[c].toLowerCase().equals(prefix[c])) {
                prefix[c] = String.valueOf(prefix[c].charAt(0));
            } else {
                break;
            }
        }

        Map<String, String> result = new HashMap<String, String>();
        for (String s : src) {
            int prefixLen = prefix.length;

            String[] names = s.split("\\.");
            System.arraycopy(prefix, 0, names, 0, prefixLen);

            String dense = "";
            for (String n : names) {
                dense += n + ".";
            }

            if (dense.endsWith(".")) {
                dense = dense.substring(0, dense.length() - 1);
            }

            result.put(s, dense);
        }

        return result;
    }


}