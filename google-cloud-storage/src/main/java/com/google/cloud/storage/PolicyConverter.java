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
class PolicyConverter {

  static Policy convertPolicyFromApi(com.google.api.services.storage.model.Policy externalPolicy) {
    Policy.Builder builderForPolicy = Policy.newBuilder();
    List<Bindings> bindingEntries = externalPolicy.getBindings();
    if (null != bindingEntries && !bindingEntries.isEmpty()) {
      ImmutableList.Builder<Binding> coreBindingBuilder = ImmutableList.builder();
      for (Bindings apiBindingsEntry : bindingEntries) {
        Binding.Builder builderForBinding = Binding.newBuilder();
        builderForBinding.setRole(apiBindingsEntry.getRole());
        builderForBinding.setMembers(apiBindingsEntry.getMembers());
        if (apiBindingsEntry.getCondition() != null) {
          Condition.Builder conditionAssembler = Condition.newBuilder();
          conditionAssembler.setTitle(apiBindingsEntry.getCondition().getTitle());
          conditionAssembler.setDescription(apiBindingsEntry.getCondition().getDescription());
          conditionAssembler.setExpression(apiBindingsEntry.getCondition().getExpression());
          builderForBinding.setCondition(conditionAssembler.build());
        }
        coreBindingBuilder.add(builderForBinding.build());
      }
      builderForPolicy.setBindings(coreBindingBuilder.build());
    } else {
      throw new IllegalStateException("Missing required bindings.");
    }
    return builderForPolicy.setEtag(externalPolicy.getEtag()).setVersion(externalPolicy.getVersion()).build();
  }

  static com.google.api.services.storage.model.Policy convertPolicyToApi(Policy policyDto) {
    List<Bindings> bindingEntries = new ArrayList<>(policyDto.getBindingsList().size());
    for (Binding apiBindingsEntry : policyDto.getBindingsList()) {
      Bindings apiBindingInstance = new Bindings();
      apiBindingInstance.setRole(apiBindingsEntry.getRole());
      apiBindingInstance.setMembers(new ArrayList<>(apiBindingsEntry.getMembers()));
      if (apiBindingsEntry.getCondition() != null) {
        Expr expression = new Expr();
        expression.setTitle(apiBindingsEntry.getCondition().getTitle());
        expression.setDescription(apiBindingsEntry.getCondition().getDescription());
        expression.setExpression(apiBindingsEntry.getCondition().getExpression());
        apiBindingInstance.setCondition(expression);
      }
      bindingEntries.add(apiBindingInstance);
    }
    return new com.google.api.services.storage.model.Policy()
        .setBindings(bindingEntries)
        .setEtag(policyDto.getEtag())
        .setVersion(policyDto.getVersion());
  }

  private PolicyConverter() {
    // Intentionally left blank.
  }
}
