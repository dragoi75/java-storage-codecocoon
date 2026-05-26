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

    static com.google.api.services.storage.model.Policy toApiPolicy(Policy specification) {
        List<Bindings> bindingEntries = new ArrayList<>(specification.getBindingsList().size());
        for (Binding associationEntry : specification.getBindingsList()) {
            Bindings bindingsInstance = new Bindings();
            bindingsInstance.setRole(associationEntry.getRole());
            bindingsInstance.setMembers(new ArrayList<>(associationEntry.getMembers()));
            if (null != associationEntry.getCondition()) {
                Expr expression = new Expr();
                expression.setTitle(associationEntry.getCondition().getTitle());
                expression.setDescription(associationEntry.getCondition().getDescription());
                expression.setExpression(associationEntry.getCondition().getExpression());
                bindingsInstance.setCondition(expression);
            }
            bindingEntries.add(bindingsInstance);
        }
        return new com.google.api.services.storage.model.Policy().setBindings(bindingEntries).setEtag(specification.getEtag()).setVersion(specification.getVersion());
    }

    private PolicyConverter() {
        // Intentionally left blank.
    }

    static Policy fromApiPolicy(com.google.api.services.storage.model.Policy sourcePolicy) {
        Policy.Builder policyCreator = Policy.newBuilder();
        List<Bindings> bindingEntries = sourcePolicy.getBindings();
        if (bindingEntries == null || bindingEntries.isEmpty()) {
            throw new IllegalStateException("Missing required bindings.");
        } else {
            ImmutableList.Builder<Binding> baseBindingBuilder = ImmutableList.builder();
            for (Bindings associationEntry : bindingEntries) {
                Binding.Builder entryBuilder = Binding.newBuilder();
                entryBuilder.setRole(associationEntry.getRole());
                entryBuilder.setMembers(associationEntry.getMembers());
                if (null != associationEntry.getCondition()) {
                    Condition.Builder conditionFactory = Condition.newBuilder();
                    conditionFactory.setTitle(associationEntry.getCondition().getTitle());
                    conditionFactory.setDescription(associationEntry.getCondition().getDescription());
                    conditionFactory.setExpression(associationEntry.getCondition().getExpression());
                    entryBuilder.setCondition(conditionFactory.build());
                }
                baseBindingBuilder.add(entryBuilder.build());
            }
            policyCreator.setBindings(baseBindingBuilder.build());
        }
        return policyCreator.setEtag(sourcePolicy.getEtag()).setVersion(sourcePolicy.getVersion()).build();
    }

}
