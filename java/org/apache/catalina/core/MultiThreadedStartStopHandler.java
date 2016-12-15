/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.catalina.core;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadedStartStopHandler extends StartStopHandler {
    private final ThreadPoolExecutor startStopExecutor;
    private static final Log log = LogFactory.getLog(MultiThreadedStartStopHandler.class);

    MultiThreadedStartStopHandler(String name, int threadCount) {
        int actualThreadCount = getStartStopThreadsInternal(threadCount);
        BlockingQueue<Runnable> startStopQueue = new LinkedBlockingQueue<>();
        startStopExecutor = new ThreadPoolExecutor(actualThreadCount,actualThreadCount, 10,
                TimeUnit.SECONDS,
                startStopQueue, new StartStopThreadFactory(name + "-startStop-"));
        startStopExecutor.allowCoreThreadTimeOut(true);
    }

    @Override
    void start(Container[] children) throws LifecycleException{
        List<Future<Void>> results = new ArrayList<>();
        for(Container child : children) {
            results.add(startStopExecutor.submit(new StartChild(child)));
        }
        handleFutures(results, "containerBase.threadedStartFailed");
    }

    @Override
    void stop(Container[] children) throws LifecycleException{
        List<Future<Void>> results = new ArrayList<>();
        for(Container child : children) {
            results.add(startStopExecutor.submit(new StopChild(child)));
        }
    }

    @Override
    void stop() {
        this.startStopExecutor.shutdownNow();
    }

    private void handleFutures(List<Future<Void>> results, String key) throws LifecycleException {
        boolean fail = false;
        for (Future<Void> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString(key), e);
                fail = true;
            }
        }
        if (fail) {
            throw new LifecycleException(
                    sm.getString(key));
        }
    }

    /**
     * Handles the special values.
     */
    private static int getStartStopThreadsInternal(int threadCount) {
        int result = threadCount;

        // Positive values are unchanged
        if (result > 0) {
            return result;
        }

        // Zero == Runtime.getRuntime().availableProcessors()
        // -ve  == Runtime.getRuntime().availableProcessors() + value
        // These two are the same
        result = Runtime.getRuntime().availableProcessors() + result;
        if (result < 1) {
            result = 1;
        }
        return result;
    }

    // ----------------------------- Inner classes used with start/stop Executor

    private static class StartChild implements Callable<Void> {

        private Container child;

        public StartChild(Container child) {
            this.child = child;
        }

        @Override
        public Void call() throws LifecycleException {
            child.start();
            return null;
        }
    }

    private static class StopChild implements Callable<Void> {

        private Container child;

        public StopChild(Container child) {
            this.child = child;
        }

        @Override
        public Void call() throws LifecycleException {
            if (child.getState().isAvailable()) {
                child.stop();
            }
            return null;
        }
    }

    private static class StartStopThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        public StartStopThreadFactory(String namePrefix) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(group, r, namePrefix + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
