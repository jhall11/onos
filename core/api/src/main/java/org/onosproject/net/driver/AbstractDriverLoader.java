/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.net.driver;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;

/**
 * Abstract bootstrapper for loading and registering driver definitions that
 * are dependent on the default driver definitions.
 */
@Component
public abstract class AbstractDriverLoader extends AbstractIndependentDriverLoader {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DefaultDriverProviderService defaultDriverProviderService;

    /**
     * Creates a new loader for resource with the specified path.
     *
     * @param path drivers definition XML resource path
     */
    protected AbstractDriverLoader(String path) {
        super(path);
    }

}
