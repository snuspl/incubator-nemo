#!/usr/bin/python3
#
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

import getopt, sys
import pprint

from train import *

# ########################################################
# MAIN FUNCTION
# ########################################################
try:
    opts, args = getopt.getopt(sys.argv[1:], "hs:d:r:", ["dagsummary=", "dagpropertydir=", "resourceinfo="])
except getopt.GetoptError:
    print('nemo_xgboost_property_optimization.py -s <dagsummary> -d <dagpropertydir> -r <resourceinfo>')
    sys.exit(2)
dagsummary = None
dagpropertydir = None
resourceinfo = None
for opt, arg in opts:
    if opt == '-h':
        print('nemo_xgboost_property_optimization.py -s <dagsummary> -d <dagpropertydir> -r <resourceinfo>')
        sys.exit()
    elif opt in ("-s", "--dagsummary"):
        dagpropertydir = arg
    elif opt in ("-d", "--dagpropertydir"):
        dagpropertydir = arg
    elif opt in ("-r", "--resourceinfo"):
        resourceinfo = arg

data = Data()
trees = train(data, dagpropertydir)

# Let's process the data now.
if dagpropertydir:
    dag_properties = data.process_individual_property_json(dagpropertydir)
    print(dag_properties)
if resourceinfo:
    resource_data = read_resource_info(resourceinfo)
    print(resource_data)

# Handle the generated trees
results = {}
print("\nGenerated Trees:")
for t in trees.values():
    results = dict_union(results, t.importance_dict())
    # print(t)

print("\nImportanceDict")
print(json.dumps(results, indent=2))

print("\nSummary")
resultsJson = []
for k, v in results.items():
    for kk, vv in v.items():
        # k is feature, kk is split, and vv is val
        feature_id = int(k[1:])
        i, key, tpe = data.transform_id_to_keypair(feature_id)  # ex. (id), org.apache.nemo.common.ir.vertex.executionproperty.ParallelismProperty/java.lang.Integer, pattern
        how = 'greater' if vv > 0 else 'smaller'
        result_string = f'{key} should be {vv} ({how}) than {kk}'
        print(result_string)
        classes = key.split('/')
        key_class = classes[0]  # ex. org.apache.nemo.common.ir.vertex.executionproperty.ParallelismProperty
        value_class = classes[1] if len(classes) > 1 else ''  # ex. java.lang.Integer
        value = data.transform_id_to_value(key, data.derive_value_from(feature_id, key, kk, vv))
        if value:  # Only returned when the EP is valid
            resultsJson.append({'type': tpe, 'ID': i, 'EPKeyClass': key_class, 'EPValueClass': value_class, 'EPValue': value})

# Question: Manually use this resource information in the optimization?
# cluster_information = read_resource_info(resourceinfo)
# print("CLUSTER:\n", cluster_information)

print("RESULT:")
pprint.pprint(resultsJson)

with open("results.out", "w") as file:
    file.write(json.dumps(resultsJson, indent=2))

