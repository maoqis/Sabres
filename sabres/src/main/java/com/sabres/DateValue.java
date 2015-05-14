/*
 * Copyright 2015 Tamir Shomer
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
package com.sabres;

import java.util.Date;

final class DateValue extends SabresValue<Date> {

    DateValue(Date value) {
        super(value);
    }

    @Override
    String toSql() {
        return String.valueOf(getValue().getTime());
    }

    @Override
    public String toString() {
        return getValue().toString();
    }

    @Override
    SabresDescriptor getDescriptor() {
        return new SabresDescriptor(SabresDescriptor.Type.Date);
    }
}
