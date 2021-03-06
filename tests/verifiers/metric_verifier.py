# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Verfier for common impalad metrics

# List of metrics that should be equal to zero when there are no outstanding queries.
METRIC_LIST = [
               # TODO (IMPALA-3377): Re-enable
               # "impala-server.backends.client-cache.clients-in-use", disabled as a
               # work-around due to IMPALA-3327.
               #"impala-server.backends.client-cache.clients-in-use",
               "impala-server.hash-table.total-bytes",
               "impala-server.io-mgr.num-open-files",
               # Disable checking of mem-pool.total-bytes DUE TO IMPALA-1057
               #"impala-server.mem-pool.total-bytes",
               "impala-server.num-files-open-for-insert",
               # Disable checking of num-missing-volume-id due to IMPALA-467
               # "impala-server.scan-ranges.num-missing-volume-id",
               ]

class MetricVerifier(object):
  """Reuseable class that can verify common metrics"""
  def __init__(self, impalad_service, metrics_list=[]):
    """Initialize module given an ImpalaService object"""
    self.impalad_service = impalad_service
    self.initial_metrics = {}
    for metric in metrics_list:
      self.initial_metrics[metric] = self.impalad_service.get_metric_value(metric)

  def verify_metrics_are_zero(self, timeout=60):
    """Test that all the metric in METRIC_LIST are 0"""
    for metric in METRIC_LIST:
      self.wait_for_metric(metric, 0, timeout)

  def verify_no_open_files(self, timeout=60):
    """Tests there are no open files"""
    self.wait_for_metric("impala-server.num-files-open-for-insert", 0, timeout)
    self.wait_for_metric("impala-server.io-mgr.num-open-files", 0, timeout)

  def verify_num_unused_buffers(self, timeout=60):
    """Test that all buffers are unused"""
    buffers =\
        self.impalad_service.get_metric_value("impala-server.io-mgr.num-buffers")
    self.wait_for_metric("impala-server.io-mgr.num-unused-buffers", buffers,
        timeout)

  def wait_for_metric(self, metric_name, expected_value, timeout=60):
    self.impalad_service.wait_for_metric_value(metric_name, expected_value, timeout)

  def wait_for_metric_reset(self, metric_name, timeout=60):
    if metric_name in self.initial_metrics:
      self.impalad_service.wait_for_metric_value(metric_name,
          self.initial_metrics[metric_name], timeout)
