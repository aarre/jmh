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
package org.openjdk.jmh.runner;

import org.openjdk.jmh.logic.results.IterationData;
import org.openjdk.jmh.output.format.OutputFormat;
import org.openjdk.jmh.profile.Profiler;
import org.openjdk.jmh.profile.ProfilerFactory;
import org.openjdk.jmh.runner.options.BaseOptions;
import org.openjdk.jmh.runner.parameters.MicroBenchmarkParameters;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;


/**
 * Base class for all microbenchmarks handlers.
 */
public abstract class BaseMicroBenchmarkHandler implements MicroBenchmarkHandler{

    /**
     * Name of micro benchmark
     */
    protected final String microbenchmark;

    /**
     * Thread-pool for threads executing the benchmark tasks
     */
    protected final ExecutorService executor;

    // (Aleksey) Forgive me, Father, for I have sinned.
    protected final ThreadLocal<InstanceProvider> threadLocal;

    protected final OutputFormat format;

    private final List<Profiler> registeredProfilers;

    public BaseMicroBenchmarkHandler(OutputFormat format, String microbenchmark, final Class<?> clazz, BaseOptions options, MicroBenchmarkParameters executionParams) {
        this.microbenchmark = microbenchmark;
        this.registeredProfilers = createProfilers(options);
        this.executor = createExecutor(executionParams.getMaxThreads(), microbenchmark);
        this.threadLocal = new ThreadLocal<InstanceProvider>() {
            @Override
            protected InstanceProvider initialValue() {
                return new EagerInstanceProvider(clazz);
            }
        };
        this.format = format;
    }

    private static List<Profiler> createProfilers(BaseOptions options) {
        List<Profiler> list = new ArrayList<Profiler>();
        // register the profilers
        for (ProfilerFactory.Profilers prof : options.getProfilers()) {
            list.add(prof.createInstance(options.isVerbose()));
        }
        return list;
    }

    private static final ExecutorType EXECUTOR_TYPE = Enum.valueOf(ExecutorType.class, System.getProperty("harness.executor", ExecutorType.FIXED_TPE.toString()));

    private enum ExecutorType {

        /**
         * Use Executors.newCachedThreadPool
         */
        CACHED_TPE,

        /**
         * Use Executors.newFixedThreadPool
         */
        FIXED_TPE,

        /**
         * Use new ForkJoinPool (JDK 7+)
         */
        FJP,

        /**
         * Use ForkJoinUtils.getDefaultFJPool (JDK 8+)
         */
        FJP_SINGLE_UTILS,
    }

    static ExecutorService createExecutor(int maxThreads, String prefix) {

        // (Aleksey):
        // requires some of the reflection magic to untie from JDK 7/8 compile-time dependencies

        switch (EXECUTOR_TYPE) {
            case CACHED_TPE:
                return Executors.newCachedThreadPool(new HarnessThreadFactory(prefix));
            case FIXED_TPE:
                return Executors.newFixedThreadPool(maxThreads, new HarnessThreadFactory(prefix));
            case FJP:
                try {
                    Constructor<?> c = Class.forName("java.util.concurrent.ForkJoinPool").getConstructor(int.class);
                    return (ExecutorService) c.newInstance(maxThreads);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            case FJP_SINGLE_UTILS:
                try {
                    Method m = Class.forName("java.util.concurrent.ForkJoinUtils").getMethod("defaultFJPool");
                    return (ExecutorService) m.invoke(null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
        }

        throw new IllegalStateException("Can not instantiate executor service");
    }

    /**
     * Forces ExecutorService to terminate.
     * This method returns only if requested executor had shut down.
     * If executor is failing to shut down regardless of multiple shutdown()s, this method will never return.
     *
     * @param executor service to shutdown
     */
    static void shutdownExecutor(ExecutorService executor) {
        if (EXECUTOR_TYPE == ExecutorType.FJP_SINGLE_UTILS) {
            // this is a system-wide executor, don't shutdown
            return;
        }

        if (executor == null) {
            return;
        }

        while (true) {
            executor.shutdown();

            try {
                if (executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            Logger.getLogger(BaseMicroBenchmarkHandler.class.getName())
                    .warning("Failed to stop executor service " + executor + ", trying again; check for the unaccounted running threads");
        }
    }

    protected void stopProfilers(IterationData iterationResults) {
        // stop profilers
        for (Profiler prof : registeredProfilers) {
            try {
                iterationResults.addProfileResult(prof.endProfile());
            } catch (Throwable ex) {
                log(ex);
            }
        }
    }

    protected void startProfilers() {
        // start profilers
        for (Profiler prof : registeredProfilers) {
            try {
                prof.startProfile();
            } catch (Throwable ex) {
                log(ex);
            }
        }
    }

    protected void log(Throwable ex) {
        format.exception(ex);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return microbenchmark;
    }

    /**
     * Status of the ExecutorPool
     *
     * @return true if it is shut down, else false
     */
    boolean isExecutorShutdown() {
        return executor.isShutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        shutdownExecutor(executor);
    }

    public interface InstanceProvider {

        /**
         * Gets the benchmark instance. This call will lazily instantiate benchmark instance.
         * @return benchmark instance.
         * @throws RuntimeException if something goes wrong
         */
        public Object getInstance();

    }

    /**
     * Gets the benchmark instance. This call will lazily instantiate benchmark instance.
     */
    public static class EagerInstanceProvider implements InstanceProvider {

        private final Object instance;

        public EagerInstanceProvider(Class<?> clazz) {
            try {
                instance = clazz.newInstance();
            } catch (InstantiationException e) {
                throw new RuntimeException("Class " + clazz.getName() + " instantiation error ", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Class " + clazz.getName() + " instantiation error ", e);
            }
        }

        @Override
        public Object getInstance() {
            return instance;
        }
    }

    /**
     * Gets the benchmark instance. This call will lazily instantiate benchmark instance.
     */
    public static class LazyInstanceProvider implements InstanceProvider {

        private final Class<?> clazz;

        private volatile Object instance;

        public LazyInstanceProvider(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public Object getInstance() {
            if (instance == null) {
                synchronized (this) {
                    if (instance == null) {
                        try {
                            instance = clazz.newInstance();
                        } catch (InstantiationException e) {
                            throw new RuntimeException("Class " + clazz.getName() + " instantiation error ", e);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("Class " + clazz.getName() + " instantiation error ", e);
                        }
                    }
                }
            }
            return instance;
        }
    }

}
