package org.infinispan.security.mappers;

import org.infinispan.Cache;
import org.infinispan.commons.api.Lifecycle;
import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.context.Flag;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.registry.InternalCacheRegistry;
import org.infinispan.security.MutablePrincipalRoleMapper;
import org.infinispan.security.actions.SecurityActions;

import java.security.Principal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ClusterRoleMapper. This class implements both a {@link MutablePrincipalRoleMapper} storing the mappings in a
 * persistent replicated internal cache named <code>org.infinispan.ROLES</code>
 *
 * @author Tristan Tarrant
 * @since 7.0
 */
@Scope(Scopes.GLOBAL)
public class ClusterRoleMapper implements MutablePrincipalRoleMapper, Lifecycle {
   @Inject
   EmbeddedCacheManager cacheManager;
   @Inject
   InternalCacheRegistry internalCacheRegistry;
   public static final String CLUSTER_ROLE_MAPPER_CACHE = "org.infinispan.ROLES";
   private Cache<String, RoleSet> clusterRoleMap;
   private Cache<String, RoleSet> clusterRoleReadMap;
   private NameRewriter nameRewriter = NameRewriter.IDENTITY_REWRITER;

   @Override
   public void start() {
      initializeInternalCache();
      clusterRoleMap = cacheManager.getCache(CLUSTER_ROLE_MAPPER_CACHE);
      clusterRoleReadMap = clusterRoleMap.getAdvancedCache().withFlags(Flag.SKIP_CACHE_LOAD, Flag.CACHE_MODE_LOCAL);
   }

   @Override
   public void stop() { }

   @Override
   public Set<String> principalToRoles(Principal principal) {
      String name = nameRewriter.rewriteName(principal.getName());
      if (clusterRoleReadMap == null) {
         return Collections.singleton(name);
      }
      RoleSet roleSet = clusterRoleReadMap.get(name);
      if (roleSet != null && !roleSet.roles.isEmpty()) {
         return roleSet.roles;
      } else {
         return Collections.singleton(name);
      }
   }

   private void initializeInternalCache() {
      GlobalConfiguration globalConfiguration = SecurityActions.getCacheManagerConfiguration(cacheManager);
      CacheMode cacheMode = globalConfiguration.isClustered() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
      ConfigurationBuilder cfg = new ConfigurationBuilder();
      cfg.clustering().cacheMode(cacheMode)
            .stateTransfer().fetchInMemoryState(true).awaitInitialTransfer(globalConfiguration.isClustered())
            .security().authorization().disable();
      internalCacheRegistry.registerInternalCache(CLUSTER_ROLE_MAPPER_CACHE, cfg.build(), EnumSet.of(InternalCacheRegistry.Flag.PERSISTENT));
   }

   @Override
   public void grant(String roleName, String principalName) {
      RoleSet roleSet = clusterRoleMap.computeIfAbsent(principalName, n -> new RoleSet());
      roleSet.roles.add(roleName);
      clusterRoleMap.put(principalName, roleSet);
   }

   @Override
   public void deny(String roleName, String principalName) {
      RoleSet roleSet = clusterRoleMap.computeIfAbsent(principalName, n -> new RoleSet());
      roleSet.roles.remove(roleName);
      clusterRoleMap.put(principalName, roleSet);
   }

   @Override
   public void denyAll(String principal) {
      clusterRoleMap.remove(principal);
   }

   @Override
   public Set<String> list(String principalName) {
      RoleSet roleSet = clusterRoleReadMap.get(principalName);
      if (roleSet != null) {
         return Collections.unmodifiableSet(roleSet.roles);
      } else {
         return Collections.singleton(principalName);
      }
   }

   @Override
   public String listAll() {
      StringBuilder sb = new StringBuilder();
      for (RoleSet set : clusterRoleReadMap.values()) {
         sb.append(set.roles.toString());
      }
      return sb.toString();
   }

   @Override
   public Set<String> listPrincipals() {
      return clusterRoleReadMap.keySet().stream().collect(Collectors.toSet());
   }

   @Override
   public Set<Map.Entry<String, RoleSet>> listPrincipalsAndRoleSet() {
      return clusterRoleReadMap.entrySet().stream().collect(Collectors.toSet());
   }

   @Override
   public Set<String> listPrincipalsByRole(String role) {
      return clusterRoleReadMap.entrySet().stream()
            .map(e -> {
               if (e.getValue() != null) {
                  return e.getValue().getRoles().contains(role) ? e.getKey() : null;
               }
               return null;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
   }

   public void nameRewriter(NameRewriter nameRewriter) {
      this.nameRewriter = nameRewriter;
   }

   public NameRewriter nameRewriter() {
      return nameRewriter;
   }

   @ProtoTypeId(ProtoStreamTypeIds.ROLE_SET)
   public static class RoleSet {
      @ProtoField(number = 1, collectionImplementation = HashSet.class)
      final Set<String> roles;

      RoleSet() {
         this(new HashSet<>());
      }

      @ProtoFactory
      RoleSet(Set<String> roles) {
         this.roles = roles;
      }


      public Set<String> getRoles() {
         return roles;
      }
   }
}
