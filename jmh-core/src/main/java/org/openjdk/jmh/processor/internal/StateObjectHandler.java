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
package org.openjdk.jmh.processor.internal;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.logic.Control;
import org.openjdk.jmh.util.internal.HashMultimap;
import org.openjdk.jmh.util.internal.Multimap;
import org.openjdk.jmh.util.internal.TreesetMultimap;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class StateObjectHandler {

    private final ProcessingEnvironment processingEnv;
    private final Multimap<String, StateObject> args;
    private final Map<String, StateObject> implicits;
    private final Set<StateObject> stateObjects;
    private final Multimap<StateObject, HelperMethodInvocation> helpersByState;

    private final Map<String, Integer> globalIndexByType = new HashMap<String, Integer>();
    private final Map<String, Integer> groupIndexByType = new HashMap<String, Integer>();
    private final Map<String, Integer> localIndexByType = new HashMap<String, Integer>();

    private final HashMap<String, String> collapsedTypes = new HashMap<String, String>();
    private int collapsedIndex = 0;

    private final HashMap<String, String> paddedTypes = new HashMap<String, String>();
    private int paddedIndex = 0;

    public StateObjectHandler(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
        this.args = new HashMultimap<String, StateObject>();
        this.implicits = new HashMap<String, StateObject>();
        this.stateObjects = new HashSet<StateObject>();
        this.helpersByState = new TreesetMultimap<StateObject, HelperMethodInvocation>();
    }

    private String getPaddedType(String type) {
        String padded = paddedTypes.get(type);
        if (padded == null) {
            padded = "padded_" + (paddedIndex++);
            paddedTypes.put(type, padded);
        }
        return padded;
    }


    public void bindArg(ExecutableElement execMethod, TypeElement type) {
        State ann = type.getAnnotation(State.class);
        if (ann != null) {
            bindState(execMethod, type, ann.value(), null);
        } else {
            throw new IllegalStateException("The method parameter is not a @State: " + type);
        }
    }

    public void bindImplicit(TypeElement type, String label) {
        bindImplicit(type, label, Scope.Thread);
    }

    public void bindImplicit(TypeElement type, String label, Scope scope) {
        State ann = type.getAnnotation(State.class);
        bindState(null, type, (ann != null) ? ann.value() : scope, label);
    }

    private void bindState(ExecutableElement execMethod, TypeElement element, Scope scope, String implicitLabel) {
        Integer index;
        String className = element.asType().toString();
        switch (scope) {
            case Benchmark: {
                index = globalIndexByType.get(className);
                if (index == null) {
                    index = 0;
                    globalIndexByType.put(className, index);
                }
                break;
            }
            case Group:
                index = groupIndexByType.get(className);
                if (index == null) {
                    index = 0;
                    groupIndexByType.put(className, index);
                }
                break;
            case Thread: {
                index = localIndexByType.get(className);
                if (index == null) {
                    index = -1;
                }
                index++;
                localIndexByType.put(className, index);
                break;
            }
            default:
                throw new IllegalStateException("Unknown scope: " + scope);
        }

        StateObject so;
        if (implicitLabel != null) {
            so = new StateObject(className, getPaddedType(className), scope, "f_" + implicitLabel, "l_" + implicitLabel);
            implicits.put(implicitLabel, so);
        } else {
            String identifier = collapseTypeName(className) + index;
            so = new StateObject(className, getPaddedType(className), scope, "f_" + identifier, "l_" + identifier);
            args.put(execMethod.getSimpleName().toString(), so);
        }

        stateObjects.add(so);

        // walk the type hierarchy up to discover inherited helper methods
        TypeElement walk = element;
        do {
            for (ExecutableElement m : ElementFilter.methodsIn(walk.getEnclosedElements())) {
                Setup setupAnn = m.getAnnotation(Setup.class);
                if (setupAnn != null) {
                    helpersByState.put(so, new HelperMethodInvocation(m.getSimpleName().toString(), so, setupAnn.value(), HelperType.SETUP));
                }

                TearDown tearDownAnn = m.getAnnotation(TearDown.class);
                if (tearDownAnn != null) {
                    helpersByState.put(so, new HelperMethodInvocation(m.getSimpleName().toString(), so, tearDownAnn.value(), HelperType.TEARDOWN));
                }
            }
        } while ((walk = (TypeElement) processingEnv.getTypeUtils().asElement(walk.getSuperclass())) != null);
    }

    public String getArgList(Element method) {
        StringBuilder sb = new StringBuilder();

        int i = 0;
        for (StateObject so : args.get(method.getSimpleName().toString())) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(so.toLocal());
            i++;
        }
        return sb.toString();
    }

    public String getTypeArgList(Element method) {
        StringBuilder sb = new StringBuilder();

        int i = 0;
        for (StateObject so : args.get(method.getSimpleName().toString())) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(so.toTypeDef());
            i++;
        }
        return sb.toString();
    }

    public static Collection<StateObject> cons(Collection<StateObject> l1) {
        SortedSet<StateObject> r = new TreeSet<StateObject>(StateObject.ID_COMPARATOR);
        r.addAll(l1);
        return r;
    }

    public static Collection<StateObject> cons(Collection<StateObject> l1, Collection<StateObject> l2) {
        SortedSet<StateObject> r = new TreeSet<StateObject>(StateObject.ID_COMPARATOR);
        r.addAll(l1);
        r.addAll(l2);
        return r;
    }

    public Collection<String> getHelperBlock(String method, Level helperLevel, HelperType type) {

        Collection<StateObject> states = cons(args.get(method), implicits.values());

        // Look for the offending methods.
        // This will be used to skip the irrelevant blocks for state objects down the stream.
        Set<StateObject> hasHelpers = new HashSet<StateObject>();
        for (StateObject so : states) {
            for (HelperMethodInvocation hmi : helpersByState.get(so)) {
                if (hmi.helperLevel == helperLevel) hasHelpers.add(so);
            }
        }

        // Handle Thread object helpers
        List<String> result = new ArrayList<String>();
        for (StateObject so : states) {
            if (so.scope != Scope.Thread) continue;
            if (!hasHelpers.contains(so)) continue;

            for (HelperMethodInvocation hmi : helpersByState.get(so)) {
                if (hmi.helperLevel == helperLevel && hmi.type == type) {
                    result.add("" + so.localIdentifier + "." + hmi.name + "();");
                }
            }
        }

        // Handle Benchmark object helpers
        for (StateObject so : states) {
            if (so.scope != Scope.Benchmark) continue;
            if (!hasHelpers.contains(so)) continue;

            switch (type) {
                case SETUP:
                    result.add("synchronized (" + so.fieldIdentifier + ") {");
                    result.add("    if (!" + so.fieldIdentifier + "_" + helperLevel + "_inited) {");
                    result.add("        " + so.fieldIdentifier + "_" + helperLevel + "_inited = true;");
                    break;
                case TEARDOWN:
                    result.add("synchronized (" + so.fieldIdentifier + ") {");
                    result.add("    if (" + so.fieldIdentifier + "_" + helperLevel + "_inited) {");
                    result.add("    " + so.fieldIdentifier + "_" + helperLevel + "_inited = false;");
                    break;
                default:
                    throw new IllegalStateException("Unknown helper type: " + type);
            }

            for (HelperMethodInvocation hmi : helpersByState.get(so)) {
                if (hmi.helperLevel == helperLevel && hmi.type == type) {
                    result.add("        " + so.localIdentifier + "." + hmi.name + "();");
                }
            }

            result.add("    }");
            result.add("}");
        }

        // Handle Group object handlers
        for (StateObject so : states) {
            if (so.scope != Scope.Group) continue;
            if (!hasHelpers.contains(so)) continue;

            switch (type) {
                case SETUP:
            result.add("synchronized (" + so.localIdentifier + ") {");
            result.add("    Boolean inited = group_" + helperLevel + "_inited.get(" + so.localIdentifier + ");");
            result.add("    if (inited == null || inited == false) {");
            result.add("        group_" + helperLevel + "_inited.put(" + so.localIdentifier + ", Boolean.TRUE);");
            break;
            case TEARDOWN:
                result.add("synchronized (" + so.localIdentifier + ") {");
                result.add("    Boolean inited = group_" + helperLevel + "_inited.get(" + so.localIdentifier + ");");
                result.add("    if (inited == null || inited == true) {");
                result.add("        group_" + helperLevel + "_inited.put(" + so.localIdentifier + ", Boolean.FALSE);");
                break;
            default:
                throw new IllegalStateException("Unknown helper type: " + type);
        }

            for (HelperMethodInvocation hmi : helpersByState.get(so)) {
                if (hmi.helperLevel == helperLevel && hmi.type == type) {
                    result.add("        " + so.localIdentifier + "." + hmi.name + "();");
                }
            }

            result.add("    }");
            result.add("}");
        }

        return result;
    }

    public Collection<String> getInvocationSetups(Element method) {
        return getHelperBlock(method.getSimpleName().toString(), Level.Invocation, HelperType.SETUP);
    }

    public Collection<String> getInvocationTearDowns(Element method) {
        return getHelperBlock(method.getSimpleName().toString(), Level.Invocation, HelperType.TEARDOWN);
    }

    public Collection<String> getIterationSetups(Element method) {
        return getHelperBlock(method.getSimpleName().toString(), Level.Iteration, HelperType.SETUP);
    }

    public Collection<String> getIterationTearDowns(Element method) {
        return getHelperBlock(method.getSimpleName().toString(), Level.Iteration, HelperType.TEARDOWN);
    }

    public Collection<String> getRunSetups(Element method) {
        return getHelperBlock(method.getSimpleName().toString(), Level.Trial, HelperType.SETUP);
    }

    public Collection<String> getRunTearDowns(Element method) {
        return getHelperBlock(method.getSimpleName().toString(), Level.Trial, HelperType.TEARDOWN);
    }

    public List<String> getStateInitializers() {
        List<String> result = new ArrayList<String>();
        for (StateObject so : cons(stateObjects)) {
            if (so.scope != Scope.Benchmark) continue;

            result.add("");
            result.add("static volatile " + so.type + " " + so.fieldIdentifier + ";");
            result.add("");
            result.add(so.type + " tryInit_" + so.fieldIdentifier + "(" + so.type + " val) throws Throwable {");
            result.add("    if (" + so.fieldIdentifier + " == null) {");
            result.add("        synchronized(this.getClass()) {");
            result.add("            if (" + so.fieldIdentifier + " == null) {");
            for (HelperMethodInvocation mi : helpersByState.get(so)) {
                if (mi.helperLevel == Level.Trial && mi.type == HelperType.SETUP) {
                    result.add("                val." + mi.name + "();");
                }
            }
            result.add("                " + so.fieldIdentifier + " = val;");
            result.add("                synchronized (" + so.fieldIdentifier + ") {");
            result.add("                    " + so.fieldIdentifier + "_" + Level.Trial + "_inited = true;");
            result.add("                }");
            result.add("            }");
            result.add("        }");
            result.add("    }");
            result.add("    return " + so.fieldIdentifier + ";");
            result.add("}");
        }

        for (StateObject so : cons(stateObjects)) {
            if (so.scope != Scope.Thread) continue;

            result.add("");
            result.add(so.type + " " + so.fieldIdentifier + ";");
            result.add("");
            result.add(so.type + " tryInit_" + so.fieldIdentifier + "(" + so.type + " val) throws Throwable {");
            result.add("    if (" + so.fieldIdentifier + " == null) {");
            for (HelperMethodInvocation mi : helpersByState.get(so)) {
                if (mi.helperLevel == Level.Trial && mi.type == HelperType.SETUP) {
                    result.add("         val." + mi.name + "();");
                }
            }
            result.add("          " + so.fieldIdentifier + " = val;");
            result.add("          " + so.fieldIdentifier + "_" + Level.Trial + "_inited = true;");
            result.add("    }");
            result.add("    return " + so.fieldIdentifier + ";");
            result.add("}");
        }

        for (StateObject so : cons(stateObjects)) {
            if (so.scope != Scope.Group) continue;

            result.add("");
            result.add("static java.util.Map<Integer, " + so.type + "> " + so.fieldIdentifier + "_map = java.util.Collections.synchronizedMap(new java.util.HashMap<Integer, " + so.type + ">());");
            result.add("");
            result.add(so.type + " tryInit_" + so.fieldIdentifier + "(int groupId, " + so.type + " val) throws Throwable {");
            result.add("    if (!" + so.fieldIdentifier + "_map.containsKey(groupId)) {");
            result.add("        synchronized(this.getClass()) {");
            result.add("            if (!" + so.fieldIdentifier + "_map.containsKey(groupId)) {");
            for (HelperMethodInvocation mi : helpersByState.get(so)) {
                if (mi.helperLevel == Level.Trial && mi.type == HelperType.SETUP) {
                    result.add("                val." + mi.name + "();");
                }
            }
            result.add("                " + so.fieldIdentifier + "_map.put(groupId, val);");
            result.add("                synchronized (val) {");
            result.add("                    group_" + Level.Trial + "_inited.put(val, Boolean.TRUE);");
            result.add("                }");
            result.add("            }");
            result.add("        }");
            result.add("    }");
            result.add("    return " + so.fieldIdentifier + "_map.get(groupId);");
            result.add("}");
        }
        return result;
    }

    public List<String> getStateGetters(Element method) {
        List<String> result = new ArrayList<String>();
        for (StateObject so : cons(args.get(method.getSimpleName().toString()), implicits.values())) {
            switch (so.scope) {
                case Benchmark:
                case Thread:
                    result.add(so.type + " " + so.localIdentifier + " = tryInit_" + so.fieldIdentifier + "(new " + so.paddedType + "());");
                    break;
                case Group:
                    result.add(so.type + " " + so.localIdentifier + " = tryInit_" + so.fieldIdentifier + "(groupId, new " + so.paddedType + "());");
                    break;
                default:
                    throw new IllegalStateException("Unhandled scope: " + so.scope);
            }
        }
        return result;
    }

    public List<String> getStateOverrides() {
        Set<String> visited = new HashSet<String>();

        List<String> result = new ArrayList<String>();
        for (StateObject so : cons(stateObjects)) {
            if (!visited.add(so.paddedType)) continue;
            result.add("static final class " + so.paddedType + " extends " + so.type + " {");
            result.add("   private volatile int jmh_auto_generated_pad01;");
            result.add("   private volatile int jmh_auto_generated_pad02;");
            result.add("   private volatile int jmh_auto_generated_pad03;");
            result.add("   private volatile int jmh_auto_generated_pad04;");
            result.add("   private volatile int jmh_auto_generated_pad05;");
            result.add("   private volatile int jmh_auto_generated_pad06;");
            result.add("   private volatile int jmh_auto_generated_pad07;");
            result.add("   private volatile int jmh_auto_generated_pad08;");
            result.add("   private volatile int jmh_auto_generated_pad09;");
            result.add("   private volatile int jmh_auto_generated_pad10;");
            result.add("   private volatile int jmh_auto_generated_pad11;");
            result.add("   private volatile int jmh_auto_generated_pad12;");
            result.add("   private volatile int jmh_auto_generated_pad13;");
            result.add("   private volatile int jmh_auto_generated_pad14;");
            result.add("   private volatile int jmh_auto_generated_pad15;");
            result.add("   private volatile int jmh_auto_generated_pad16;");
            result.add("}");
        }
        return result;
    }


    public void clearArgs() {
        args.clear();
    }

    public Collection<String> getFields() {
        Collection<String> result = new ArrayList<String>();
        for (StateObject so : cons(stateObjects)) {
            switch (so.scope) {
                case Benchmark:
                    result.add("private static boolean " + so.fieldIdentifier + "_"    + Level.Trial + "_inited;");
                    result.add("private static boolean " + so.fieldIdentifier + "_"    + Level.Iteration  + "_inited;");
                    result.add("private static boolean " + so.fieldIdentifier + "_"    + Level.Invocation + "_inited;");
                    break;
                case Group:
                    break;
                case Thread:
                    result.add("private boolean " + so.fieldIdentifier + "_"    + Level.Trial + "_inited;");
                    result.add("private boolean " + so.fieldIdentifier + "_"    + Level.Iteration  + "_inited;");
                    result.add("private boolean " + so.fieldIdentifier + "_"    + Level.Invocation + "_inited;");
                    break;
                default:
                    throw new IllegalStateException("Unhandled scope: " + so.scope);
            }
        }

        result.add("private static final java.util.Map<Object, Boolean> group_" + Level.Trial + "_inited = java.util.Collections.synchronizedMap(new java.util.HashMap<Object, Boolean>());");
        result.add("private static final java.util.Map<Object, Boolean> group_" + Level.Iteration + "_inited = java.util.Collections.synchronizedMap(new java.util.HashMap<Object, Boolean>());");
        result.add("private static final java.util.Map<Object, Boolean> group_" + Level.Invocation + "_inited = java.util.Collections.synchronizedMap(new java.util.HashMap<Object, Boolean>());");

        result.add("private static final java.util.concurrent.atomic.AtomicInteger threadSelector = new java.util.concurrent.atomic.AtomicInteger();");
        result.add("private int threadId = 0;");
        result.add("private boolean threadId_inited = false;");
        return result;
    }

    private String collapseTypeName(String e) {
        if (collapsedTypes.containsKey(e)) {
            return collapsedTypes.get(e);
        }

        String[] strings = e.split("\\.");
        String name = strings[strings.length - 1].toLowerCase();

        String collapsedName = name + (collapsedIndex++) + "_";
        collapsedTypes.put(e, collapsedName);
        return collapsedName;
    }

    public StateObject getImplicit(String label) {
        return implicits.get(label);
    }

    public Collection<StateObject> getControls() {
        Collection<StateObject> s = new ArrayList<StateObject>();
        for (StateObject so : cons(args.values())) {
            if (so.type.equals(Control.class.getName())) {
                s.add(so);
            }
        }
        return s;
    }
}