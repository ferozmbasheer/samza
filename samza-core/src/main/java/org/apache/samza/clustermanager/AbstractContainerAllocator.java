/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.samza.clustermanager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.apache.samza.SamzaException;
import org.apache.samza.config.ClusterManagerConfig;
import org.apache.samza.config.Config;
import org.apache.samza.config.TaskConfig;
import org.apache.samza.job.CommandBuilder;
import org.apache.samza.job.ShellCommandBuilder;
import org.apache.samza.util.ReflectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * {@link AbstractContainerAllocator} makes requests for physical resources to the resource manager and also runs
 * a processor on an allocated physical resource. Sub-classes should override the assignResourceRequests()
 * method to assign resource requests according to some strategy.
 *
 * See {@link ContainerAllocator} and {@link HostAwareContainerAllocator} for two such strategies
 *
 * This class is not thread-safe. This class is used in the refactored code path as called by run-jc.sh
 */
public abstract class AbstractContainerAllocator implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(AbstractContainerAllocator.class);

  /* State that controls the lifecycle of the allocator thread*/
  private volatile boolean isRunning = true;

  /**
   * Config and derived config objects
   */
  private final TaskConfig taskConfig;
  private final Config config;

  /**
   * Classloader for creating objects from config
   */
  private final ClassLoader pluginClassLoader;

  /**
   * A ClusterResourceManager for the allocator to request for resources.
   */
  protected final ClusterResourceManager clusterResourceManager;
  /**
   * The allocator sleeps for allocatorSleepIntervalMs before it polls its queue for the next request
   */
  protected final int allocatorSleepIntervalMs;
  /**
   * Each container currently has the same configuration - memory, and numCpuCores.
   */
  protected final int containerMemoryMb;
  protected final int containerNumCpuCores;

  /**
   * State corresponding to num failed containers, running processors etc.
   */
  protected final SamzaApplicationState state;

  /**
   * ResourceRequestState indicates the state of all unfulfilled and allocated container requests
   */
  protected final ResourceRequestState resourceRequestState;

  public AbstractContainerAllocator(ClusterResourceManager containerProcessManager,
      ResourceRequestState resourceRequestState,
      Config config,
      SamzaApplicationState state,
      ClassLoader pluginClassLoader) {
    ClusterManagerConfig clusterManagerConfig = new ClusterManagerConfig(config);
    this.clusterResourceManager = containerProcessManager;
    this.allocatorSleepIntervalMs = clusterManagerConfig.getAllocatorSleepTime();
    this.resourceRequestState = resourceRequestState;
    this.containerMemoryMb = clusterManagerConfig.getContainerMemoryMb();
    this.containerNumCpuCores = clusterManagerConfig.getNumCores();
    this.taskConfig = new TaskConfig(config);
    this.state = state;
    this.config = config;
    this.pluginClassLoader = pluginClassLoader;
  }

  /**
   * Continually schedule processors to run on resources obtained from the cluster manager.
   * The loop frequency is governed by thread sleeps for allocatorSleepIntervalMs ms.
   *
   * Terminates when the isRunning flag is cleared.
   */
  @Override
  public void run() {
    while (isRunning) {
      try {
        assignResourceRequests();

        // Move delayed requests that are ready to the active request queue
        resourceRequestState.sendPendingDelayedResourceRequests();

        // Release extra resources and update the entire system's state
        resourceRequestState.releaseExtraResources();

        Thread.sleep(allocatorSleepIntervalMs);
      } catch (InterruptedException e) {
        log.warn("Got InterruptedException in AllocatorThread.", e);
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        log.error("Got unknown Exception in AllocatorThread.", e);
      }
    }
  }

  /**
   * Assigns resources received from the cluster manager to processors.
   */
  protected abstract void assignResourceRequests();

  /**
   * Updates the request state and runs a processor on the specified host. Assumes a resource
   * is available on the preferred host, so the caller must verify that before invoking this method.
   *
   * @param request             the {@link SamzaResourceRequest} which is being handled.
   * @param preferredHost       the preferred host on which the processor should be run or
   *                            {@link ResourceRequestState#ANY_HOST} if there is no host preference.
   * @throws                    SamzaException if there is no allocated resource in the specified host.
   */
  protected void runStreamProcessor(SamzaResourceRequest request, String preferredHost) {
    CommandBuilder builder = getCommandBuilder(request.getProcessorId());
    // Get the available resource
    SamzaResource resource = peekAllocatedResource(preferredHost);
    if (resource == null) {
      throw new SamzaException("Expected resource for Processor ID: " + request.getProcessorId() + " was unavailable on host: " + preferredHost);
    }

    // Update state
    resourceRequestState.updateStateAfterAssignment(request, preferredHost, resource);
    String processorId = request.getProcessorId();

    // Run processor on resource
    log.info("Found Container ID: {} for Processor ID: {} on host: {} for request creation time: {}.",
        resource.getContainerId(), processorId, preferredHost, request.getRequestTimestamp());

    // Update processor state as "pending" and then issue a request to launch it. It's important to perform the state-update
    // prior to issuing the request. Otherwise, there's a race where the response callback may arrive sooner and not see
    // the processor as "pending" (SAMZA-2117)

    state.pendingProcessors.put(processorId, resource);

    clusterResourceManager.launchStreamProcessor(resource, builder);
  }

  /**
   * Called during initial request for resources
   *
   * @param processorToHostMapping A Map of [processorId, hostName], where processorId is the ID of the Samza processor
   *                               to run on the resource. hostName is the host on which the resource should be allocated.
   *                               The hostName value is null, either
   *                                - when host-affinity has never been enabled, or
   *                                - when host-affinity is enabled and job is run for the first time
   *                                - when the number of containers has been increased.
   */
  public abstract void requestResources(Map<String, String> processorToHostMapping);

  /**
   * Checks if this allocator has a pending resource request with a request timestamp equal to or earlier than the current
   * timestamp.
   * @return {@code true} if there is a pending request, {@code false} otherwise.
   */
  protected final boolean hasReadyPendingRequest() {
    return peekReadyPendingRequest().isPresent();
  }

  /**
   * Retrieves, but does not remove, the next pending request in the queue with the {@link SamzaResourceRequest#getRequestTimestamp()}
   * that is greater than the current timestamp.
   *
   * @return  the pending request or {@code null} if there is no pending request.
   */
  protected final Optional<SamzaResourceRequest> peekReadyPendingRequest() {
    SamzaResourceRequest pendingRequest = resourceRequestState.peekPendingRequest();
    return Optional.ofNullable(pendingRequest);
  }

  /**
   * Requests a resource from the cluster manager
   * @param processorId Samza processor ID that will be run when a resource is allocated for this request
   * @param preferredHost name of the host that you prefer to run the processor on
   */
  public final void requestResource(String processorId, String preferredHost) {
    requestResourceWithDelay(processorId, preferredHost, Duration.ZERO);
  }

  /**
   * Requests a resource from the cluster manager with a request timestamp of the current time plus the specified delay.
   * @param processorId Samza processor ID that will be run when a resource is allocated for this request
   * @param preferredHost name of the host that you prefer to run the processor on
   * @param delay the {@link Duration} to add to the request timestamp
   */
  public final void requestResourceWithDelay(String processorId, String preferredHost, Duration delay) {
    SamzaResourceRequest request = getResourceRequestWithDelay(processorId, preferredHost, delay);
    issueResourceRequest(request);
  }

  /**
   * Creates a {@link SamzaResourceRequest} to send to the cluster manager
   * @param processorId Samza processor ID that will be run when a resource is allocated for this request
   * @param preferredHost name of the host that you prefer to run the processor on
   * @return the created request
   */
  public final SamzaResourceRequest getResourceRequest(String processorId, String preferredHost) {
    return getResourceRequestWithDelay(processorId, preferredHost, Duration.ZERO);
  }

  /**
   * Creates a {@link SamzaResourceRequest} to send to the cluster manager with a request timestamp of the current time
   * plus the specified delay.
   * @param processorId Samza processor ID that will be run when a resource is allocated for this request
   * @param preferredHost name of the host that you prefer to run the processor on
   * @param delay the {@link Duration} to add to the request timestamp
   * @return the created request
   */
  public final SamzaResourceRequest getResourceRequestWithDelay(String processorId, String preferredHost, Duration delay) {
    return new SamzaResourceRequest(this.containerNumCpuCores, this.containerMemoryMb, preferredHost, processorId, Instant.now().plus(delay));
  }

  public final void issueResourceRequest(SamzaResourceRequest request) {
    resourceRequestState.addResourceRequest(request);
    state.containerRequests.incrementAndGet();
    if (ResourceRequestState.ANY_HOST.equals(request.getPreferredHost())) {
      state.anyHostRequests.incrementAndGet();
    } else {
      state.preferredHostRequests.incrementAndGet();
    }
  }

  /**
   * Returns true if there are resources allocated on a host.
   * @param host  the host for which a resource is needed.
   * @return      {@code true} if there is a resource allocated for the specified host, {@code false} otherwise.
   */
  protected boolean hasAllocatedResource(String host) {
    return peekAllocatedResource(host) != null;
  }

  /**
   * Retrieves, but does not remove, the first allocated resource on the specified host.
   *
   * @param host  the host on which a resource is needed.
   * @return      the first {@link SamzaResource} allocated for the specified host or {@code null} if there isn't one.
   */
  protected SamzaResource peekAllocatedResource(String host) {
    return resourceRequestState.peekResource(host);
  }

  /**
   * Returns a command builder with the build environment configured with the processorId.
   * @param processorId to configure the builder with.
   * @return the constructed builder object
   */
  private CommandBuilder getCommandBuilder(String processorId) {
    String cmdBuilderClassName = taskConfig.getCommandClass(ShellCommandBuilder.class.getName());
    CommandBuilder cmdBuilder =
        ReflectionUtil.getObj(this.pluginClassLoader, cmdBuilderClassName, CommandBuilder.class);

    cmdBuilder.setConfig(config).setId(processorId).setUrl(state.jobModelManager.server().getUrl());
    return cmdBuilder;
  }

  /**
   * Adds allocated samzaResource to a synchronized buffer of allocated resources.
   * See allocatedResources in {@link ResourceRequestState}
   *
   * @param samzaResource returned by the ContainerManager
   */
  public final void addResource(SamzaResource samzaResource) {
    resourceRequestState.addResource(samzaResource);
  }

  /**
   * Releases a single resource based on containerId.
   * @param containerId container ID
   */
  public final void releaseResource(String containerId) {
    resourceRequestState.releaseResource(containerId);
  }

  /**
   * Stops the Allocator. Setting this flag to false exits the allocator loop.
   */
  public void stop() {
    isRunning = false;
  }
}
