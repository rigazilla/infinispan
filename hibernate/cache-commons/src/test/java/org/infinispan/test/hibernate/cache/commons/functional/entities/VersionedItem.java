package org.infinispan.test.hibernate.cache.commons.functional.entities;


/**
 * @author Steve Ebersole
 */
public class VersionedItem {
   private Long id;
   private Long version;
   private String name;
   private String description;

   public Long getId() {
      return id;
   }

   public void setId(Long id) {
      this.id = id;
   }

   public Long getVersion() {
      return version;
   }

   public void setVersion(Long version) {
      this.version = version;
   }

   public String getDescription() {
      return description;
   }

   public void setDescription(String description) {
      this.description = description;
   }

   public String getName() {
      return name;
   }

   public void setName(String name) {
      this.name = name;
   }

}
