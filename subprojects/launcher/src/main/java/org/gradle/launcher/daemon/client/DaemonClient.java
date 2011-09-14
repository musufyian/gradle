/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.launcher.daemon.client;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.BuildClientMetaData;
import org.gradle.initialization.GradleLauncherAction;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.GradleLauncherActionExecuter;
import org.gradle.launcher.daemon.protocol.Build;
import org.gradle.launcher.daemon.protocol.BusyException;
import org.gradle.launcher.daemon.protocol.CommandComplete;
import org.gradle.launcher.daemon.protocol.Result;
import org.gradle.logging.internal.OutputEvent;
import org.gradle.logging.internal.OutputEventListener;
import org.gradle.messaging.remote.internal.Connection;

/**
 * The client piece of the build daemon.
 *
 * <p>Protocol is this:</p>
 *
 * <ol> <li>Client connects to the server.</li>
 *
 * <li>Client sends a {@link org.gradle.launcher.daemon.protocol.Command} message.</li>
 *
 * <li>Server sends zero or more {@link org.gradle.logging.internal.OutputEvent} messages. Note that the server may send output messages before it receives the command message. </li>
 *
 * <li>Server sends a {@link org.gradle.launcher.daemon.protocol.CommandComplete} message.</li>
 *
 * <li>Connection is closed.</li>
 *
 * </ol>
 */
public class DaemonClient implements GradleLauncherActionExecuter<BuildActionParameters> {
    private static final Logger LOGGER = Logging.getLogger(DaemonClient.class);
    private final DaemonConnector connector;
    private final BuildClientMetaData clientMetaData;
    private final OutputEventListener outputEventListener;

    public DaemonClient(DaemonConnector connector, BuildClientMetaData clientMetaData, OutputEventListener outputEventListener) {
        this.connector = connector;
        this.clientMetaData = clientMetaData;
        this.outputEventListener = outputEventListener;
    }

    /**
     * Stops all daemons, if any is running.
     */
    public void stop() {
        Connection<Object> connection = connector.maybeConnect();
        if (connection == null) {
            LOGGER.lifecycle("No Gradle daemons are running.");
            return;
        }

        LOGGER.lifecycle("At least one daemon is running. Sending stop command...");
        //iterate and stop all daemons
        while (connection != null) {
            new StopDispatcher().dispatch(clientMetaData, connection);
            LOGGER.lifecycle("Gradle daemon stopped.");
            connection = connector.maybeConnect();
        }
    }

    /**
     * Executes the given action in the daemon. The action and parameters must be serializable.
     *
     * @param action The action
     * @throws org.gradle.launcher.exec.ReportedException On failure, when the failure has already been logged/reported.
     */
    public <T> T execute(GradleLauncherAction<T> action, BuildActionParameters parameters) {
        LOGGER.warn("Note: the Gradle build daemon is an experimental feature.");
        LOGGER.warn("As such, you may experience unexpected build failures. You may need to occasionally stop the daemon.");
        while(true) {
            Connection<Object> connection = connector.connect();
            try {
                Result result = (Result) runBuild(new Build(action, parameters), connection);
                return (T) result.getResult();
            } catch (BusyException e) {
                //ignore. We'll continue looping until we get a connection that is able handle a build request.
            }
        }
    }

    private CommandComplete runBuild(Build build, Connection<Object> connection) {
        try {
            //TODO SF - this may fail. We should handle it and have tests for that. It means the server is gone.
            connection.dispatch(build);
            while (true) {
                Object object = connection.receive();
                if (object instanceof CommandComplete) {
                    CommandComplete commandComplete = (CommandComplete) object;
                    if (commandComplete.getFailure() != null) {
                        throw commandComplete.getFailure();
                    }
                    return commandComplete;
                } else if (object instanceof OutputEvent) {
                    OutputEvent outputEvent = (OutputEvent) object;
                    outputEventListener.onOutput(outputEvent);
                } else if (object == null) {
                    throw new IllegalStateException(String.format("Daemon returned null after we sent %s", build));
                } else {
                    throw new IllegalStateException(String.format("Daemon returned %s (type: %s) which there is no strategy to respond to", object, object.getClass()));
                }
            }
        } finally {
            connection.stop();
        }
    }
}
