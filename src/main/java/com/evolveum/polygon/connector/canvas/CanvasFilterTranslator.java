/*
 * Copyright (C) 2024 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.polygon.connector.canvas;

import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AbstractFilterTranslator;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

public class CanvasFilterTranslator extends AbstractFilterTranslator<CanvasFilter> {

    @Override
    protected CanvasFilter createEqualsExpression(EqualsFilter filter, boolean not) {
        if (not) {
            return null;
        }

        Attribute attr = filter.getAttribute();
        if (!attr.is(Uid.NAME) && !attr.is(Name.NAME)) {
            return null;
        }

        CanvasFilter canvasFilter = new CanvasFilter();
        String value = AttributeUtil.getStringValue(attr);
        if (attr.is(Uid.NAME)) {
            canvasFilter.idEqualTo = value;
        }
        if (attr.is(Name.NAME)) {
            canvasFilter.loginEqualTo = value;
        }
        return canvasFilter;
    }
}
