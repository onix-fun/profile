<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { computeGraphMenuLayout } from "@onix/design-system";
import type { IconName, OnixGraphMenuMode, OnixTone } from "@onix/design-system";
import { activateFocusTrap } from "@/shared/lib/focusTrap";
import OnixIcon from "@/shared/ui/OnixIcon.vue";

export interface GraphMenuItem {
  id: string;
  label: string;
  meta?: string;
  icon: IconName;
  tone?: OnixTone;
  active?: boolean;
  disabled?: boolean;
  avatarUrl?: string | null;
}

type PlacedGraphMenuItem = GraphMenuItem & { x: number; y: number; width: number; height: number; column: number; row: number };

const props = withDefaults(defineProps<{
  items: GraphMenuItem[];
  mode?: OnixGraphMenuMode;
  menuLabel: string;
  closeLabel: string;
}>(), { mode: "main" });
const emit = defineEmits<{ select: [id: string]; close: [] }>();

const open = ref(false);
const root = ref<HTMLElement | null>(null);
const viewport = ref({ width: window.innerWidth, height: window.innerHeight });
let releaseFocusTrap: (() => void) | null = null;

const layout = computed(() => {
  const value = computeGraphMenuLayout({
    viewportWidth: viewport.value.width,
    viewportHeight: viewport.value.height,
    nodes: props.items.map((item) => ({ ...item, width: item.meta ? 204 : 176, height: item.meta ? 52 : 44 })),
    safeRight: 0,
    safeBottom: 0,
  });
  return { ...value, nodes: value.nodes.map((node) => ({ ...props.items.find((item) => item.id === node.id)!, ...node })) as PlacedGraphMenuItem[] };
});

function syncViewport() {
  viewport.value = {
    width: window.visualViewport?.width || window.innerWidth,
    height: window.visualViewport?.height || window.innerHeight,
  };
}

function setApplicationInert(value: boolean) {
  const content = document.querySelector<HTMLElement>("[data-onix-app-content]");
  if (content) content.inert = value;
}

async function setOpen(value: boolean) {
  if (open.value === value) return;
  open.value = value;
  releaseFocusTrap?.();
  releaseFocusTrap = null;
  setApplicationInert(value);
  if (value) {
    await nextTick();
    if (root.value) releaseFocusTrap = activateFocusTrap(root.value, close);
  }
}

function close() {
  void setOpen(false);
  emit("close");
}

function select(item: GraphMenuItem) {
  if (item.disabled) return;
  emit("select", item.id);
}

watch(() => props.mode, () => { if (open.value) void nextTick(syncViewport); });
onMounted(() => {
  syncViewport();
  window.addEventListener("resize", syncViewport);
  window.visualViewport?.addEventListener("resize", syncViewport);
});
onBeforeUnmount(() => {
  releaseFocusTrap?.();
  setApplicationInert(false);
  window.removeEventListener("resize", syncViewport);
  window.visualViewport?.removeEventListener("resize", syncViewport);
});
</script>

<template>
  <div ref="root" class="onix-graph-menu" :data-open="open" :data-mode="mode" :aria-label="menuLabel">
    <div v-if="open" class="onix-graph-menu__backdrop" aria-hidden="true" @click="close" />
    <div v-if="open" class="onix-graph-menu__stage">
      <svg class="onix-graph-menu__branches" :viewBox="`0 0 ${layout.viewport.width} ${layout.viewport.height}`" aria-hidden="true">
        <path
          v-for="(branch, index) in layout.branches"
          :key="branch.id"
          class="onix-graph-menu__branch"
          :data-onix-tone="branch.tone"
          :d="branch.path"
          pathLength="1"
          :style="{ '--onix-graph-index': index }"
        />
      </svg>
      <button
        v-for="(node, index) in layout.nodes"
        :key="node.id"
        class="onix-graph-menu__node"
        type="button"
        :data-node-id="node.id"
        :data-onix-tone="node.tone || 'neutral'"
        :aria-current="node.active ? 'page' : undefined"
        :aria-disabled="node.disabled || undefined"
        :tabindex="node.disabled ? -1 : 0"
        :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${node.width}px`, height: `${node.height}px`, '--onix-graph-index': index }"
        @click="select(node)"
      >
        <span class="onix-graph-menu__node-icon">
          <img v-if="node.avatarUrl" class="graph-node-avatar" :src="node.avatarUrl" alt="" />
          <OnixIcon v-else :name="node.icon" :size="20" />
        </span>
        <span class="onix-graph-menu__node-copy">
          <span>{{ node.label }}</span>
          <span v-if="node.meta" class="onix-graph-menu__node-meta">{{ node.meta }}</span>
        </span>
      </button>
    </div>
    <button class="onix-graph-menu__fab" type="button" :aria-label="open ? closeLabel : menuLabel" :aria-expanded="open" @click="open ? close() : setOpen(true)">
      <OnixIcon class="onix-graph-menu__fab-icon" :name="open ? 'add' : 'menu'" :size="24" />
    </button>
  </div>
</template>

<style scoped>
.graph-node-avatar { width: 100%; height: 100%; border-radius: var(--onix-radius-pill); object-fit: cover; }
</style>
