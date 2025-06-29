package org.infinispan.commands.topology;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.protostream.WrappedMessage;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.NodeVersion;
import org.infinispan.topology.CacheTopology;

/**
 * Update the stable topology.
 *
 * @author Ryan Emerson
 * @since 11.0
 */
@ProtoTypeId(ProtoStreamTypeIds.TOPOLOGY_UPDATE_STABLE_COMMAND)
public class TopologyUpdateStableCommand extends AbstractCacheControlCommand {

   final List<Address> actualMembers;

   @ProtoField(1)
   final String cacheName;

   @ProtoField(2)
   final List<UUID> persistentUUIDs;

   @ProtoField(3)
   final int rebalanceId;

   @ProtoField(4)
   final int topologyId;

   @ProtoField(5)
   final int viewId;

   @ProtoField(6)
   final WrappedMessage currentCH;

   @ProtoField(7)
   final WrappedMessage pendingCH;

   @ProtoField(8)
   final boolean topologyRestored;

   @ProtoField(9)
   List<Address> getActualMembers() {
      return actualMembers;
   }

   @ProtoFactory
   TopologyUpdateStableCommand(String cacheName, List<UUID> persistentUUIDs, int rebalanceId, int topologyId,
                               int viewId, WrappedMessage currentCH, WrappedMessage pendingCH, List<Address> actualMembers,
                               boolean topologyRestored) {
      this.currentCH = currentCH;
      this.pendingCH = pendingCH;
      this.cacheName = cacheName;
      this.actualMembers = actualMembers;
      this.persistentUUIDs = persistentUUIDs;
      this.rebalanceId = rebalanceId;
      this.topologyId = topologyId;
      this.viewId = viewId;
      this.topologyRestored = topologyRestored;
   }

   public TopologyUpdateStableCommand(String cacheName, Address origin, CacheTopology cacheTopology, int viewId) {
      super(origin);
      this.cacheName = cacheName;
      this.topologyId = cacheTopology.getTopologyId();
      this.rebalanceId = cacheTopology.getRebalanceId();
      this.currentCH = new WrappedMessage(cacheTopology.getCurrentCH());
      this.pendingCH = new WrappedMessage(cacheTopology.getPendingCH());
      this.actualMembers = cacheTopology.getActualMembers();
      this.persistentUUIDs = cacheTopology.getMembersPersistentUUIDs();
      this.viewId = viewId;
      this.topologyRestored = cacheTopology.wasTopologyRestoredFromState();
   }

   @Override
   public CompletionStage<?> invokeAsync(GlobalComponentRegistry gcr) throws Throwable {
      CacheTopology topology = new CacheTopology(topologyId, rebalanceId, topologyRestored, getCurrentCH(), getPendingCH(),
            CacheTopology.Phase.NO_REBALANCE, actualMembers, persistentUUIDs);
      return gcr.getLocalTopologyManager()
            .handleStableTopologyUpdate(cacheName, topology, origin, viewId);
   }

   public ConsistentHash getCurrentCH() {
      return (ConsistentHash) currentCH.getValue();
   }

   public ConsistentHash getPendingCH() {
      return (ConsistentHash) pendingCH.getValue();
   }

   public int getTopologyId() {
      return topologyId;
   }

   @Override
   public NodeVersion supportedSince() {
      return NodeVersion.SIXTEEN;
   }

   @Override
   public String toString() {
      return "TopologyUpdateStableCommand{" +
            "cacheName='" + cacheName + '\'' +
            ", origin=" + origin +
            ", currentCH=" + getCurrentCH() +
            ", pendingCH=" + getPendingCH() +
            ", actualMembers=" + actualMembers +
            ", persistentUUIDs=" + persistentUUIDs +
            ", rebalanceId=" + rebalanceId +
            ", topologyId=" + topologyId +
            ", viewId=" + viewId +
            '}';
   }
}
