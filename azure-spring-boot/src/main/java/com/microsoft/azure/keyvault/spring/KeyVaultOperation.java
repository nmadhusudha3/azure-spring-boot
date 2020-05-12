/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.keyvault.spring;

import com.azure.core.http.rest.PagedIterable;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class KeyVaultOperation {
    private final long cacheRefreshIntervalInMs;
    private final List<String> secretKeys;

    private final Object refreshLock = new Object();
    private final SecretClient keyVaultClient;
    private final String vaultUri;

    private final Map<String, String> keyVaultItems = new HashMap<>();
    private String[] propertyNamesArr;

    private final AtomicLong lastUpdateTime = new AtomicLong();
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public KeyVaultOperation(final SecretClient keyVaultClient,
                             String vaultUri,
                             final long refreshInterval,
                             final List<String> secretKeys) {
        this.cacheRefreshIntervalInMs = refreshInterval;
        this.secretKeys = secretKeys;
        this.keyVaultClient = keyVaultClient;
        // TODO(pan): need to validate why last '/' need to be truncated.
        this.vaultUri = StringUtils.trimTrailingCharacter(vaultUri.trim(), '/');
        fillSecretsList();
    }

    public String[] list() {
        try {
            this.rwLock.readLock().lock();
            return propertyNamesArr;
        } finally {
            this.rwLock.readLock().unlock();
        }
    }

    private String getKeyVaultSecretName(@NonNull String property) {
        if (property.matches("[a-z0-9A-Z-]+")) {
            return property.toLowerCase(Locale.US);
        } else if (property.matches("[A-Z0-9_]+")) {
            return property.toLowerCase(Locale.US).replaceAll("_", "-");
        } else {
            return property.toLowerCase(Locale.US)
                    .replaceAll("-", "")     // my-project -> myproject
                    .replaceAll("_", "")     // my_project -> myproject
                    .replaceAll("\\.", "-"); // acme.myproject -> acme-myproject
        }
    }

    /**
     * For convention we need to support all relaxed binding format from spring, these may include:
     * <table>
     * <tr><td>Spring relaxed binding names</td></tr>
     * <tr><td>acme.my-project.person.first-name</td></tr>
     * <tr><td>acme.myProject.person.firstName</td></tr>
     * <tr><td>acme.my_project.person.first_name</td></tr>
     * <tr><td>ACME_MYPROJECT_PERSON_FIRSTNAME</td></tr>
     * </table>
     * But azure keyvault only allows ^[0-9a-zA-Z-]+$ and case insensitive, so there must be some conversion
     * between spring names and azure keyvault names.
     * For example, the 4 properties stated above should be convert to acme-myproject-person-firstname in keyvault.
     *
     * @param property of secret instance.
     * @return the value of secret with given name or null.
     */
    public String get(final String property) {
        Assert.hasText(property, "property should contain text.");
        refreshKeyVaultItemsIfNeeded();
        return Optional.of(property)
                .map(this::getKeyVaultSecretName)
                .map(keyVaultItems::get)
                .orElse(null);
    }

    private void refreshKeyVaultItemsIfNeeded() {
        if (needRefreshKeyVaultItems()) {
            synchronized (this.refreshLock) {
                if (needRefreshKeyVaultItems()) {
                    this.lastUpdateTime.set(System.currentTimeMillis());
                    fillSecretsList();
                }
            }
        }
    }

    private boolean needRefreshKeyVaultItems() {
        return (secretKeys == null || secretKeys.size() == 0)
                && System.currentTimeMillis() - this.lastUpdateTime.get() > this.cacheRefreshIntervalInMs;
    }

    private void fillSecretsList() {
        try {
            this.rwLock.writeLock().lock();
            if (this.secretKeys == null || secretKeys.size() == 0) {
                this.keyVaultItems.clear();

                final PagedIterable<SecretProperties> secretProperties = keyVaultClient.listPropertiesOfSecrets();
                secretProperties.forEach(s -> {
                    final String secretName = s.getName().replace(vaultUri + "/secrets/", "");
                    addSecretIfNotExist(secretName);
                });

                this.lastUpdateTime.set(System.currentTimeMillis());
            } else {
                for (final String secretKey : secretKeys) {
                    addSecretIfNotExist(secretKey);
                }
            }
            propertyNamesArr = keyVaultItems.keySet().toArray(new String[0]);
        } finally {
            this.rwLock.writeLock().unlock();
        }
    }

    private void addSecretIfNotExist(final String name) {
        final String secretNameLowerCase = name.toLowerCase(Locale.US);
        if (!keyVaultItems.containsKey(secretNameLowerCase)) {
            keyVaultItems.put(secretNameLowerCase, getValueFromKeyVault(name));
        }
        final String secretNameSeparatedByDot = secretNameLowerCase.replaceAll("-", ".");
        if (!keyVaultItems.containsKey(secretNameSeparatedByDot)) {
            keyVaultItems.put(secretNameSeparatedByDot, getValueFromKeyVault(name));
        }
    }

    private String getValueFromKeyVault(String name) {
        return Optional.ofNullable(name)
                .map(keyVaultClient::getSecret)
                .map(KeyVaultSecret::getName)
                .orElse(null);
    }

}
