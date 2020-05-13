/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.keyvault.spring;

import org.springframework.core.env.PropertySource;

public class KeyVaultPropertySource extends PropertySource<KeyVaultOperation> {

    private final KeyVaultOperation operations;

    public KeyVaultPropertySource(KeyVaultOperation operation) {
        super(Constants.AZURE_KEYVAULT_PROPERTYSOURCE_NAME, operation);
        this.operations = operation;
    }


    public String[] getPropertyNames() {
        return this.operations.getPropertyNames();
    }


    public Object getProperty(String name) {
        return operations.get(name);
    }
}
