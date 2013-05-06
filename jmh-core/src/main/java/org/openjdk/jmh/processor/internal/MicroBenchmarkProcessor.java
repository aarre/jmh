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

import org.openjdk.jmh.annotations.MicroBenchmark;
import org.openjdk.jmh.logic.Loop;
import org.openjdk.jmh.logic.results.Result;
import org.openjdk.jmh.runner.MicroBenchmarkList;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** @author staffan.friberg@oracle.com */
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class MicroBenchmarkProcessor extends AbstractProcessor {

    private final Set<String> methods = new TreeSet<String>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton(MicroBenchmark.class.getName());
    }

    /**
     * Process all method annotated with MicroBenchmark and add them to the list of available microbenchmarks.
     * <p/>
     * {@inheritDoc}
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            if (!roundEnv.processingOver()) {
                // Still processing add all annotated methods to the set
                for (TypeElement annotation : annotations) {
                    for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                        // Check that Method has the correct
                        if (!(element instanceof ExecutableElement)
                                || !validMethodSignature((ExecutableElement) element)) {
                            processingEnv.getMessager().printMessage(Kind.ERROR,
                                    "The " + MicroBenchmark.class.getSimpleName()
                                            + " annotation only supports methods return type " + Result.class.getName()
                                            + " and a single " + Loop.class.getName() + " parameter. "
                                            + element.getEnclosingElement() + '.' + element.toString());
                        } else {
                            methods.add(element.getEnclosingElement() + "." + element.getSimpleName());
                        }
                    }

                }
            } else {
                // Processing completed, final round. Print all added methods to file
                try {
                    FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                            MicroBenchmarkList.MICROBENCHMARK_LIST.substring(1));
                    PrintWriter writer = new PrintWriter(file.openWriter());
                    for (String method : methods) {
                        writer.println(method);
                    }
                    writer.close();
                } catch (IOException ex) {
                    processingEnv.getMessager().printMessage(Kind.ERROR, "Error writing MicroBenchmark list " + ex);
                }
            }
        } catch (Throwable t) {
            processingEnv.getMessager().printMessage(Kind.ERROR, "Annotation processor had throw exception: " + t);
            t.printStackTrace(System.err);
        }
        return true;
    }

    /**
     * Check that the method signature is correct for MicroBenchmark methods
     *
     * @param element The annotated method
     * @return True iff the method has the correct signature
     */
    public static boolean validMethodSignature(ExecutableElement element) {
        if (!element.getReturnType().toString().equals(Result.class.getName())) {
            return false;
        }

        List<? extends VariableElement> variables = element.getParameters();

        if (variables.size() != 1) {
            return false;
        }

        if (!variables.get(0).asType().toString().equals(Loop.class.getName())) {
            return false;
        }

        return true;
    }
}