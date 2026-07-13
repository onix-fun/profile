<script setup lang="ts">
import { computed } from "vue";
import { NodeViewWrapper, nodeViewProps } from "@tiptap/vue-3";

const props = defineProps(nodeViewProps);

const attrs = computed(() => props.node.attrs as {
  id: string;
  name: string;
  type: "IMAGE" | "VIDEO" | "AUDIO" | "FILE";
  url: string;
});

function remove() {
  props.extension.options.onRemove?.(attrs.value.id);
  props.deleteNode();
}
</script>

<template>
  <NodeViewWrapper class="media-file-node" as="span" :data-type="attrs.type" contenteditable="false">
    <span v-if="attrs.type === 'IMAGE' && attrs.url" class="media-file-node__preview">
      <img :src="attrs.url" alt="" />
    </span>
    <span v-else-if="attrs.type === 'VIDEO' && attrs.url" class="media-file-node__preview">
      <video :src="attrs.url" muted playsinline preload="metadata" />
    </span>
    <span v-else class="media-file-node__chip">
      <i :class="attrs.type === 'AUDIO' ? 'pi pi-volume-up' : 'pi pi-file'"></i>
      <span>{{ attrs.name }}</span>
    </span>

    <span class="media-file-node__capsule">
      <i :class="attrs.type === 'IMAGE' ? 'pi pi-image' : attrs.type === 'VIDEO' ? 'pi pi-video' : attrs.type === 'AUDIO' ? 'pi pi-volume-up' : 'pi pi-file'"></i>
      <span>{{ attrs.name }}</span>
      <button type="button" aria-label="Remove file" @click.stop="remove">
        <i class="pi pi-times"></i>
      </button>
    </span>
  </NodeViewWrapper>
</template>
