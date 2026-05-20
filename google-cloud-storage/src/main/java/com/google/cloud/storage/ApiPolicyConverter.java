/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage;

import com.google.api.services.storage.model.Expr;
import com.google.api.services.storage.model.Policy.Bindings;
import com.google.cloud.Binding;
import com.google.cloud.Condition;
import com.google.cloud.Policy;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for converting between the Policy model provided by the API and the Policy model provided
 * by this library.
 */
class ApiPolicyConverter {

  static Policy convertApiPolicyToPolicy(com.google.api.services.storage.model.Policy externalPolicy) {
    Policy.Builder policyAssembler = Policy.newBuilder();
    List<Bindings> bindingList = externalPolicy.getBindings();
    if (null != bindingList && !bindingList.isEmpty()) {
      ImmutableList.Builder<Binding> coreBindingBuilder = ImmutableList.builder();
      for (Bindings associationEntry : bindingList) {
        Binding.Builder assocBuilder = Binding.newBuilder();
        assocBuilder.setRole(associationEntry.getRole());
        assocBuilder.setMembers(associationEntry.getMembers());
        if (associationEntry.getCondition() != null) {
          Condition.Builder predicateBuilder = Condition.newBuilder();
          predicateBuilder.setTitle(associationEntry.getCondition().getTitle());
          predicateBuilder.setDescription(associationEntry.getCondition().getDescription());
          predicateBuilder.setExpression(associationEntry.getCondition().getExpression());
          assocBuilder.setCondition(predicateBuilder.build());
        }
        coreBindingBuilder.add(assocBuilder.build());
      }
      policyAssembler.setBindings(coreBindingBuilder.build());
    } else {
      throw new IllegalStateException("Missing required bindings.");
    }
    return policyAssembler.setEtag(externalPolicy.getEtag()).setVersion(externalPolicy.getVersion()).build();
  }

  static com.google.api.services.storage.model.Policy convertPolicyToApiPolicy(Policy ruleSet) {
    List<Bindings> bindingList = new ArrayList<>(ruleSet.getBindingsList().size());
    for (Binding associationEntry : ruleSet.getBindingsList()) {
      Bindings bindingDto = new Bindings();
      bindingDto.setRole(associationEntry.getRole());
      bindingDto.setMembers(new ArrayList<>(associationEntry.getMembers()));
      if (associationEntry.getCondition() != null) {
        Expr evaluation = new Expr();
        evaluation.setTitle(associationEntry.getCondition().getTitle());
        evaluation.setDescription(associationEntry.getCondition().getDescription());
        evaluation.setExpression(associationEntry.getCondition().getExpression());
        bindingDto.setCondition(evaluation);
      }
      bindingList.add(bindingDto);
    }
    return new com.google.api.services.storage.model.Policy()
        .setBindings(bindingList)
        .setEtag(ruleSet.getEtag())
        .setVersion(ruleSet.getVersion());
  }

  private ApiPolicyConverter() {
    // Intentionally left blank.
  }
}
