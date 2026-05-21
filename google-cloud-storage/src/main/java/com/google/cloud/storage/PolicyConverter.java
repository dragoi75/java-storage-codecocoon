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

    static Policy fromApiPolicy(com.google.api.services.storage.model.Policy externalPolicy) {
        Policy.Builder policyFactory = Policy.newBuilder();
        List<Bindings> bindingList = externalPolicy.getBindings();
        if (bindingList == null || bindingList.isEmpty()) {
            throw new IllegalStateException("Missing required bindings.");
        } else {
            ImmutableList.Builder<Binding> baseBindingBuilder = ImmutableList.builder();
            for (Bindings incomingBinding : bindingList) {
                Binding.Builder bindingFactory = Binding.newBuilder();
                bindingFactory.setRole(incomingBinding.getRole());
                bindingFactory.setMembers(incomingBinding.getMembers());
                if (null != incomingBinding.getCondition()) {
                    Condition.Builder conditionFactory = Condition.newBuilder();
                    conditionFactory.setTitle(incomingBinding.getCondition().getTitle());
                    conditionFactory.setDescription(incomingBinding.getCondition().getDescription());
                    conditionFactory.setExpression(incomingBinding.getCondition().getExpression());
                    bindingFactory.setCondition(conditionFactory.build());
                }
                baseBindingBuilder.add(bindingFactory.build());
            }
            policyFactory.setBindings(baseBindingBuilder.build());
        }
        return policyFactory.setEtag(externalPolicy.getEtag()).setVersion(externalPolicy.getVersion()).build();
    }

    static com.google.api.services.storage.model.Policy toApiPolicy(Policy domainPolicy) {
        List<Bindings> bindingList = new ArrayList<>(domainPolicy.getBindingsList().size());
        for (Binding incomingBinding : domainPolicy.getBindingsList()) {
            Bindings externalBinding = new Bindings();
            externalBinding.setRole(incomingBinding.getRole());
            externalBinding.setMembers(new ArrayList<>(incomingBinding.getMembers()));
            if (null != incomingBinding.getCondition()) {
                Expr expression = new Expr();
                expression.setTitle(incomingBinding.getCondition().getTitle());
                expression.setDescription(incomingBinding.getCondition().getDescription());
                expression.setExpression(incomingBinding.getCondition().getExpression());
                externalBinding.setCondition(expression);
            }
            bindingList.add(externalBinding);
        }
        return new com.google.api.services.storage.model.Policy().setBindings(bindingList).setEtag(domainPolicy.getEtag()).setVersion(domainPolicy.getVersion());
    }

    private PolicyConverter() {
        // Intentionally left blank.
    }
}
