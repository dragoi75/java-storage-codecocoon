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

    private PolicyConverter() {
        // Intentionally left blank.
    }

    static Policy buildPolicyFromApi(com.google.api.services.storage.model.Policy externalPolicy) {
        Policy.Builder policyAssembler = Policy.newBuilder();
        List<Bindings> bindingEntries = externalPolicy.getBindings();
        if (bindingEntries == null || bindingEntries.isEmpty()) {
            throw new IllegalStateException("Missing required bindings.");
        } else {
            ImmutableList.Builder<Binding> baseBindingBuilder = ImmutableList.builder();
            for (Bindings bindEntry : bindingEntries) {
                Binding.Builder bindConstructor = Binding.newBuilder();
                bindConstructor.setRole(bindEntry.getRole());
                bindConstructor.setMembers(bindEntry.getMembers());
                if (null != bindEntry.getCondition()) {
                    Condition.Builder conditionAssembler = Condition.newBuilder();
                    conditionAssembler.setTitle(bindEntry.getCondition().getTitle());
                    conditionAssembler.setDescription(bindEntry.getCondition().getDescription());
                    conditionAssembler.setExpression(bindEntry.getCondition().getExpression());
                    bindConstructor.setCondition(conditionAssembler.build());
                }
                baseBindingBuilder.add(bindConstructor.build());
            }
            policyAssembler.setBindings(baseBindingBuilder.build());
        }
        return policyAssembler.setEtag(externalPolicy.getEtag()).setVersion(externalPolicy.getVersion()).build();
    }

    static com.google.api.services.storage.model.Policy buildApiPolicy(Policy ruleSet) {
        List<Bindings> bindingEntries = new ArrayList<>(ruleSet.getBindingsList().size());
        for (Binding bindEntry : ruleSet.getBindingsList()) {
            Bindings externalBinding = new Bindings();
            externalBinding.setRole(bindEntry.getRole());
            externalBinding.setMembers(new ArrayList<>(bindEntry.getMembers()));
            if (null != bindEntry.getCondition()) {
                Expr expression = new Expr();
                expression.setTitle(bindEntry.getCondition().getTitle());
                expression.setDescription(bindEntry.getCondition().getDescription());
                expression.setExpression(bindEntry.getCondition().getExpression());
                externalBinding.setCondition(expression);
            }
            bindingEntries.add(externalBinding);
        }
        return new com.google.api.services.storage.model.Policy().setBindings(bindingEntries).setEtag(ruleSet.getEtag()).setVersion(ruleSet.getVersion());
    }

}
