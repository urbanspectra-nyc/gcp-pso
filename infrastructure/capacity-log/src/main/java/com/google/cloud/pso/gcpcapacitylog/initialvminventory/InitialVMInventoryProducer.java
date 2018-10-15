/*
 * Copyright 2018 Google LLC
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

package com.google.cloud.pso.gcpcapacitylog.initialvminventory;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.cloudresourcemanager.model.Project;
import com.google.api.services.compute.model.Instance;
import com.google.cloud.pso.gcpcapacitylog.services.GCEHelper;
import com.google.common.flogger.FluentLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;

public class InitialVMInventoryProducer implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private Queue<Project> queue;
  private BlockingQueue<Object> initialVMInventoryRows;

  // Retry logic
  private ArrayList<Project> retries = new ArrayList<>();
  private static final int MAX_RETRIES = 3;

  public InitialVMInventoryProducer(Queue<Project> queue, BlockingQueue initialVMInventoryRows) {
    this.queue = queue;
    this.initialVMInventoryRows = initialVMInventoryRows;
  }

  @Override
  public void run() {
    Project project;
    while (!queue.isEmpty()) {
      project = queue.poll();

      logger.atInfo().log("Processing initial vm inventory for project: " + project.getProjectId() + ". (" + queue.size() + " projects remaining)");

      try {
        ArrayList batch = new ArrayList();
        for (Instance instance : GCEHelper.getInstancesForProject(project)) {
          batch.add(convertToBQRow(instance));
        }
        for(Object o:batch) {
          initialVMInventoryRows.put(o);
        }


      } catch (GoogleJsonResponseException e) {
        if (e.getStatusCode() == 404) {
          // Project is pending delete
          logger.atFiner().log("Project " + project.getProjectId() + " does not exist. Ignoring"
              + " project.");
        } else if (e.getStatusCode() == 403) {
          logger.atFiner().log("GCE API not activated for project: " + project.getProjectId() + ". Ignoring project.");
        } else {
          logger.atSevere().log("Error while processing project: " + project.getProjectId() + " Putting project back in queue.", e);
          queue.add(project);
        }
      } catch (Exception e) {
        // Retry logic. If the project fails more than MAX_RETRIES ignore the project. Otherwise put it back in queue for a retry.
        retries.add(project);
        if(Collections.frequency(retries, project) < MAX_RETRIES) {
          logger.atWarning().log("Error while processing project: " + project.getProjectId() + " Putting project back in queue.", e);
          queue.add(project);
        } else {
          logger.atSevere().log("Error while processing project: " + project.getProjectId() + "  Ignoring project.", e);
        }
      }
    }

  }

  protected static InitialInstanceInventoryRow convertToBQRow(Instance instance) {
    return new InitialInstanceInventoryRow(instance.getCreationTimestamp(),
        instance.getId().toString(),
        instance.getZone(),
        instance.getMachineType(), instance.getScheduling().getPreemptible(), instance.getTags(), instance.getLabels());
  }
}
