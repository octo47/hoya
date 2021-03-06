/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hoya.yarn.model.mock

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hoya.yarn.HoyaTestBase
import org.apache.hadoop.hoya.yarn.appmaster.state.AbstractRMOperation
import org.apache.hadoop.hoya.yarn.appmaster.state.AppState
import org.apache.hadoop.hoya.yarn.appmaster.state.ContainerAssignment
import org.apache.hadoop.hoya.yarn.appmaster.state.NodeInstance
import org.apache.hadoop.hoya.yarn.appmaster.state.RoleInstance
import org.apache.hadoop.hoya.yarn.appmaster.state.RoleStatus
import org.apache.hadoop.yarn.api.records.Container
import org.apache.hadoop.yarn.api.records.ContainerId
import org.apache.hadoop.yarn.api.records.ContainerState
import org.apache.hadoop.yarn.api.records.ContainerStatus
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.service.launcher.LauncherExitCodes
import org.junit.Before
import org.apache.hadoop.fs.FileSystem as HadoopFS

@CompileStatic
@Slf4j
abstract class BaseMockAppStateTest extends HoyaTestBase implements MockRoles {
  MockFactory factory = new MockFactory()
  AppState appState
  MockYarnEngine engine
  protected HadoopFS fs
  protected File historyWorkDir
  protected Path historyPath;

  @Override
  void setup() {
    super.setup()
    YarnConfiguration conf = createConfiguration()
    fs = HadoopFS.get(new URI("file:///"), conf)
    engine = createYarnEngine()
  }

  /**
   * Override point: called in setup() to create the YARN engine; can
   * be changed for different sizes and options
   * @return
   */
  public MockYarnEngine createYarnEngine() {
    return new MockYarnEngine(64, 1)
  }

  @Before
  void initApp(){

    String historyDirName = testName;
    appState = new AppState(new MockRecordFactory())
    appState.setContainerLimits(4096,64)
    
    YarnConfiguration conf = createConfiguration()
    fs = HadoopFS.get(new URI("file:///"), conf)
    historyWorkDir = new File("target/history", historyDirName)
    historyPath = new Path(historyWorkDir.toURI())
    fs.delete(historyPath, true)
    appState.buildInstance(factory.newClusterSpec(0, 0, 0),
                           new Configuration(false),
                           factory.ROLES, fs, historyPath)
  }

  abstract String getTestName();

  public RoleStatus getRole0Status() {
    return appState.lookupRoleStatus(ROLE0)
  }

  public RoleStatus getRole1Status() {
    return appState.lookupRoleStatus(ROLE1)
  }

  public RoleStatus getRole2Status() {
    return appState.lookupRoleStatus(ROLE2)
  }

  /**
   * Build a role instance from a container assignment
   * @param assigned
   * @return
   */
  RoleInstance roleInstance(ContainerAssignment assigned) {
    Container target = assigned.container
    RoleInstance ri = new RoleInstance(target)
    ri.buildUUID();
    ri.roleId = assigned.role.priority
    return ri
  }

  public void assertListEquals(List left, List right) {
    assert left.size() == right.size();
    for (int i = 0; i < left.size(); i++) {
      assert left[0] == right[0]
    }
  }

  public NodeInstance nodeInstance(long age, int live0, int live1=0, int live2=0) {
    NodeInstance ni = new NodeInstance("age${age}live[${live0},${live1},$live2]",
                                       MockFactory.ROLE_COUNT)
    ni.getOrCreate(0).lastUsed = age
    ni.getOrCreate(0).live = live0;
    if (live1 > 0) {
      ni.getOrCreate(1).live = live1;
    }
    if (live2 > 0) {
      ni.getOrCreate(2).live = live2;
    }
    return ni
  }

  /**
   * Create a container status event
   * @param c container
   * @return a status
   */
  ContainerStatus containerStatus(Container c) {
    return containerStatus(c.id)
  }

  public ContainerStatus containerStatus(ContainerId cid) {
    ContainerStatus status = ContainerStatus.newInstance(
        cid,
        ContainerState.COMPLETE,
        "",
        LauncherExitCodes.EXIT_CLIENT_INITIATED_SHUTDOWN)
    return status
  }

  /**
   * Create nodes and bring them to the started state
   * @return
   */
  protected List<RoleInstance> createAndStartNodes() {
    List<AbstractRMOperation> ops = appState.reviewRequestAndReleaseNodes()
    List<Container> allocatedContainers = engine.execute(ops)
    List<ContainerAssignment> assignments = [];
    List<AbstractRMOperation> operations = []
    appState.onContainersAllocated(allocatedContainers, assignments, operations)
    List<RoleInstance> instances = []
    for (ContainerAssignment assigned : assignments) {
      Container container = assigned.container

      int roleId = assigned.role.priority
      RoleInstance ri = roleInstance(assigned)
      instances << ri
      //tell the app it arrived
      appState.containerStartSubmitted(container, ri);
      assert appState.onNodeManagerContainerStarted(container.id)
    }
    return instances
  }

  List<ContainerId> extractContainerIds(
      List<RoleInstance> instances,
      int role) {
    List<ContainerId> cids = []
    instances.each { RoleInstance instance ->
      if (instance.roleId == role) {
        cids << instance.id
      }
    }
    return cids
  }

}
