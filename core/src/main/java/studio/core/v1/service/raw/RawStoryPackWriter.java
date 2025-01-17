/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.service.raw;

import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT;
import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT_PADDING;
import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE;
import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_NODE_NAME_TRUNCATE;
import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING;
import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_STAGE_NODE_ALIGNMENT_PADDING;
import static studio.core.v1.service.raw.RawStoryPackDTO.BINARY_ENRICHED_METADATA_TITLE_TRUNCATE;
import static studio.core.v1.service.raw.RawStoryPackDTO.SECTOR_SIZE;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import studio.core.v1.exception.StoryTellerException;
import studio.core.v1.model.ActionNode;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.Node;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.Transition;
import studio.core.v1.model.asset.MediaAsset;
import studio.core.v1.model.asset.MediaAssetType;
import studio.core.v1.model.enriched.EnrichedNodePosition;
import studio.core.v1.model.enriched.EnrichedNodeType;
import studio.core.v1.service.StoryPackWriter;
import studio.core.v1.service.raw.RawStoryPackDTO.AssetAddr;
import studio.core.v1.service.raw.RawStoryPackDTO.AssetType;
import studio.core.v1.service.raw.RawStoryPackDTO.SectorAddr;
import studio.core.v1.utils.security.SecurityUtils;

public class RawStoryPackWriter implements StoryPackWriter {

    /** Binary check, stored in base64. */
    private static final byte[] CHECK_BYTES = Base64.getMimeDecoder().decode(String.join("\n", //
            "X87Wfg5QRriuVdqMRciYiqOHpTE7d0d2sIFTfdWWLzTdOIgFyk5E4bFUWb6zSJrXX1OqF/Y2Tjiq",
            "IUmyp8HcBnMRaJJl7EkYgjeLtOm4mzMBFcw1TtxOepJNSgMD2U0i7MAjzlqtQASuoQZ9QB9j8ubi",
            "scY4ok7CpzstlZd86cbKemrby5ZNLZ6GtnIK1VrKwnuFM5FiRYad50XB59HJ3Lqb1EXCAEVdoT79",
            "jSCX9gH/jd25lzZHi5CDD2uYr7c/C53BKd0XTHOsp7fd94mHOEfWWcnJ50fNkfvH9va0dPmuZNn1",
            "V3JJeprkkEBmuEvmhaxDZrLUTaeJbiIOko4kSaHuNYDYk0MPhy1Wu8VC/7XtE/t9Jd9Cgqb+fhgI",
            "aUKzUBQPYiFYSAitSsb8zCM8OfOS1JR1jEfEm7XrqBWQ2ngPbYJBashIb4xIZ4p3ot/5Ow/4YWyZ",
            "TOOlZvd6QBvOrc6554gzXEctjzaaHuNzwjqqTTuPl49Ng5qAuidtbRw3aidGYi2vRXy4bsK173tl",
            "C1it9pkqJkgpvvXxw+mvPyQE7qh2Q9hBg5gOJfr3OeQc7Qr34J+LSOSRsBvPzDntJCdDNlPEt0lA",
            "mWIQD0usba3CRBHPhIJLqKCvifXDEFf/5Hn+gJAYTMWXDR1wKhl5Z2JFv1MsfEzli0TDGseDXh0="));

    @Override
    public void write(StoryPack pack, Path path, boolean enriched) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {

            // Write sector 1
            dos.writeShort(pack.getStageNodes().size());
            dos.writeByte(pack.isFactoryDisabled() ? 1 : 0);
            dos.writeShort(pack.getVersion());

            // Write (optional) enriched pack metadata
            int enrichedPackMetadataSize = 0;
            if (enriched && pack.getEnriched() != null) {
                writePadding(dos, BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING);
                writeTruncatedString(dos, pack.getEnriched().getTitle(), BINARY_ENRICHED_METADATA_TITLE_TRUNCATE);
                writeTruncatedString(dos, pack.getEnriched().getDescription(),
                        BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE);
                // TODO Thumbnail?
                enrichedPackMetadataSize = BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING
                        + BINARY_ENRICHED_METADATA_TITLE_TRUNCATE * 2
                        + BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE * 2;
            }
            writePadding(dos, SECTOR_SIZE - 5 - enrichedPackMetadataSize); // Skip to end of sector

            // Count action nodes and assets (with sizes) and attribute a sector address
            // (offset) to each
            Map<SectorAddr, ActionNode> actionNodesMap = new TreeMap<>();
            Map<String, AssetAddr> assetsHashes = new TreeMap<>();
            Map<AssetAddr, byte[]> assetsData = new TreeMap<>();

            // offset
            int nextFreeOffset = pack.getStageNodes().size();
            // action nodes
            for (Transition t : pack.transitions()) {
                if (!actionNodesMap.containsValue(t.getActionNode())) {
                    actionNodesMap.put(new SectorAddr(nextFreeOffset++), t.getActionNode());
                }
            }
            // distinct images
            nextFreeOffset = prepareAssets(AssetType.IMAGE, pack, nextFreeOffset, assetsHashes, assetsData);
            // distinct audio
            nextFreeOffset = prepareAssets(AssetType.AUDIO, pack, nextFreeOffset, assetsHashes, assetsData);

            // Write stage nodes (from sector 2)
            writeStageNodes(dos, pack.getStageNodes(), assetsHashes, actionNodesMap, enriched);

            // Write action sectors
            int currentOffset = writeActionNode(dos, pack.getStageNodes(), actionNodesMap, enriched);

            // Write assets (images / audio)
            for (var assetEntry : assetsData.entrySet()) {
                // First sector to write
                AssetAddr assetAddr = assetEntry.getKey();
                // Skip to the beginning of the sector, if needed
                while (assetAddr.getOffset() > currentOffset) {
                    writePadding(dos, SECTOR_SIZE);
                    currentOffset++;
                }
                // Asset to write
                byte[] assetBytes = assetEntry.getValue();
                // Write all bytes
                int overflow = 0;
                dos.write(assetBytes, 0, assetBytes.length);
                overflow = assetBytes.length % SECTOR_SIZE;
                // Skip to end of sector
                if (overflow > 0) {
                    writePadding(dos, SECTOR_SIZE - overflow);
                }
                currentOffset += assetAddr.getSize();
            }
            // Write check bytes
            dos.write(CHECK_BYTES, 0, CHECK_BYTES.length);
            // cleanup
            Stream.of(actionNodesMap, assetsHashes, assetsData).forEach(Map::clear);
        }
    }

    private static int prepareAssets(AssetType assetType, StoryPack pack, int nextFreeOffset,
            Map<String, AssetAddr> assetsHashes, Map<AssetAddr, byte[]> assetsData) {
        boolean isImage = (AssetType.IMAGE == assetType);
        // distinct media
        for (MediaAsset asset : new HashSet<>(pack.assets(isImage))) {
            byte[] data = asset.getRawData();
            String hash = SecurityUtils.sha1Hex(data);
            MediaAssetType targetType = isImage ? MediaAssetType.BMP : MediaAssetType.WAV;
            if (targetType != asset.getType()) {
                throw new StoryTellerException(
                        "Cannot write binary pack file from a compressed story pack. Uncompress the pack assets first.");
            }
            int size = data.length;
            int sectors = size / SECTOR_SIZE;
            if (size % SECTOR_SIZE > 0) {
                sectors++;
            }
            AssetAddr addr = new AssetAddr(assetType, nextFreeOffset, sectors);
            assetsHashes.put(hash, addr);
            assetsData.put(addr, data);
            nextFreeOffset += sectors;
        }
        return nextFreeOffset;
    }

    private static void writeStageNodes(DataOutputStream dos, List<StageNode> stageNodes,
            Map<String, AssetAddr> assetsHashes, Map<SectorAddr, ActionNode> actionNodesMap, boolean enriched)
            throws IOException {
        for (StageNode stageNode : stageNodes) {
            // UUID
            UUID nodeUuid = UUID.fromString(stageNode.getUuid());
            dos.writeLong(nodeUuid.getMostSignificantBits());
            dos.writeLong(nodeUuid.getLeastSignificantBits());

            // Assets
            writeMediaAsset(dos, assetsHashes, stageNode.getImage());
            writeMediaAsset(dos, assetsHashes, stageNode.getAudio());

            // Transitions
            writeTransition(dos, actionNodesMap, stageNode.getOkTransition());
            writeTransition(dos, actionNodesMap, stageNode.getHomeTransition());

            // Control settings
            ControlSettings cs = stageNode.getControlSettings();
            dos.writeShort(cs.isWheelEnabled() ? 1 : 0);
            dos.writeShort(cs.isOkEnabled() ? 1 : 0);
            dos.writeShort(cs.isHomeEnabled() ? 1 : 0);
            dos.writeShort(cs.isPauseEnabled() ? 1 : 0);
            dos.writeShort(cs.isAutoJumpEnabled() ? 1 : 0);

            // Write (optional) enriched node metadata
            int enrichedNodeMetadataSize = 0;
            if (enriched && stageNode.getEnriched() != null) {
                writePadding(dos, BINARY_ENRICHED_METADATA_STAGE_NODE_ALIGNMENT_PADDING);
                enrichedNodeMetadataSize = BINARY_ENRICHED_METADATA_STAGE_NODE_ALIGNMENT_PADDING
                        + writeEnrichedNodeMetadata(dos, stageNode);
            }
            // Skip to end of sector
            writePadding(dos, SECTOR_SIZE - 54 - enrichedNodeMetadataSize);
        }
    }

    private static void writeMediaAsset(DataOutputStream dos, Map<String, AssetAddr> assetsHashes, MediaAsset asset)
            throws IOException {
        if (asset == null) {
            dos.writeInt(-1);
            dos.writeInt(-1);
            return;
        }
        String assetHash = SecurityUtils.sha1Hex(asset.getRawData());
        AssetAddr assetAddr = assetsHashes.get(assetHash);
        dos.writeInt(assetAddr.getOffset());
        dos.writeInt(assetAddr.getSize());
    }

    private static void writeTransition(DataOutputStream dos, Map<SectorAddr, ActionNode> actionNodesMap,
            Transition transition) throws IOException {
        if (transition == null) {
            dos.writeShort(-1);
            dos.writeShort(-1);
            dos.writeShort(-1);
            return;
        }
        int nodeOffset = getKeys(actionNodesMap, transition.getActionNode()) //
                .findFirst().map(SectorAddr::getOffset).orElse(-1);
        dos.writeShort(nodeOffset);
        dos.writeShort(transition.getActionNode().getOptions().size());
        dos.writeShort(transition.getOptionIndex());
    }

    private static int writeActionNode(DataOutputStream dos, List<StageNode> stageNodes,
            Map<SectorAddr, ActionNode> actionNodesMap, boolean enriched) throws IOException {
        int currentOffset = stageNodes.size();
        for (var actionNodeEntry : actionNodesMap.entrySet()) {
            // Sector to write
            SectorAddr actionNodeAddr = actionNodeEntry.getKey();
            // Add padding to the beginning of the sector, if needed
            while (actionNodeAddr.getOffset() > currentOffset) {
                writePadding(dos, SECTOR_SIZE);
                currentOffset++;
            }
            // Node to write
            ActionNode actionNode = actionNodeEntry.getValue();
            for (StageNode stageNode : actionNode.getOptions()) {
                int stageNodeOffset = stageNodes.indexOf(stageNode);
                dos.writeShort(stageNodeOffset);
            }
            // Write (optional) enriched node metadata
            int enrichedNodeMetadataSize = 0;
            if (enriched && actionNode.getEnriched() != null) {
                int alignmentOverflow = 2 * (actionNode.getOptions().size())
                        % BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT;
                int alignmentPadding = BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT_PADDING
                        + (alignmentOverflow > 0 ? BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT - alignmentOverflow
                                : 0);
                writePadding(dos, alignmentPadding);
                enrichedNodeMetadataSize = alignmentPadding + writeEnrichedNodeMetadata(dos, actionNode);
            }
            // Skip to end of sector
            writePadding(dos, SECTOR_SIZE - 2 * (actionNode.getOptions().size()) - enrichedNodeMetadataSize);
            currentOffset++;
        }
        return currentOffset;
    }

    private static int writeEnrichedNodeMetadata(DataOutputStream dos, Node node) throws IOException {
        writeTruncatedString(dos, node.getEnriched().getName(), BINARY_ENRICHED_METADATA_NODE_NAME_TRUNCATE);
        String nodeGroupId = node.getEnriched().getGroupId();
        if (nodeGroupId != null) {
            UUID groupId = UUID.fromString(nodeGroupId);
            dos.writeLong(groupId.getMostSignificantBits());
            dos.writeLong(groupId.getLeastSignificantBits());
        } else {
            writePadding(dos, 16);
        }
        EnrichedNodeType nodeType = node.getEnriched().getType();
        if (nodeType != null) {
            dos.writeByte(nodeType.getCode());
        } else {
            dos.writeByte(0x00);
        }
        EnrichedNodePosition nodePosition = node.getEnriched().getPosition();
        if (nodePosition != null) {
            dos.writeShort(nodePosition.getX());
            dos.writeShort(nodePosition.getY());
        } else {
            writePadding(dos, 4);
        }
        return BINARY_ENRICHED_METADATA_NODE_NAME_TRUNCATE * 2 + 16 + 1 + 4;
    }

    private static void writeTruncatedString(DataOutputStream dos, String str, int maxChars) throws IOException {
        if (str != null) {
            int strLength = Math.min(str.length(), maxChars);
            dos.writeChars(str.substring(0, strLength));
            int remaining = maxChars - strLength;
            if (remaining > 0) {
                writePadding(dos, remaining * 2);
            }
        } else {
            writePadding(dos, maxChars * 2);
        }
    }

    private static void writePadding(DataOutputStream dos, int length) throws IOException {
        byte[] padding = new byte[length];
        dos.write(padding, 0, length);
    }

    private static <K, V> Stream<K> getKeys(Map<K, V> map, V value) {
        return map.entrySet().stream() //
                .filter(entry -> value.equals(entry.getValue())) //
                .map(Map.Entry::getKey);
    }
}
