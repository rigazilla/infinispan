package org.infinispan.xsite.commands;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.infinispan.commands.remote.BaseRpcCommand;
import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.util.ByteString;
import org.infinispan.xsite.BackupSender;
import org.infinispan.xsite.status.SiteState;
import org.infinispan.xsite.status.TakeOfflineManager;

/**
 * Get the offline status of a {@link BackupSender}.
 *
 * @author Ryan Emerson
 * @since 11.0
 */
@Deprecated(since = "15.1", forRemoval = true)
@ProtoTypeId(ProtoStreamTypeIds.XSITE_OFFLINE_STATUS_COMMAND)
public class XSiteOfflineStatusCommand extends BaseRpcCommand {

   public static final int COMMAND_ID = 99;

   @ProtoField(number = 2)
   final String siteName;

   @ProtoFactory
   public XSiteOfflineStatusCommand(ByteString cacheName, String siteName) {
      super(cacheName);
      this.siteName = siteName;
   }

   @Override
   public CompletionStage<?> invokeAsync(ComponentRegistry registry) {
      TakeOfflineManager takeOfflineManager = registry.getTakeOfflineManager().running();
      return CompletableFuture.completedFuture(takeOfflineManager.getSiteState(siteName) != SiteState.ONLINE);
   }

   @Override
   public final boolean isReturnValueExpected() {
      return true;
   }

   @Override
   public byte getCommandId() {
      return COMMAND_ID;
   }

   @Override
   public String toString() {
      return "XSiteOfflineStatusCommand{" +
            "siteName='" + siteName + '\'' +
            '}';
   }
}
