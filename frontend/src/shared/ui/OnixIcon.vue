<script setup lang="ts">
import type { IconName } from "@onix/design-system";
import spriteUrl from "@onix/design-system/icons/sprite.svg?url";

withDefaults(defineProps<{ name: IconName; size?: number; label?: string }>(), {
  size: 20,
  label: undefined,
});
</script>

<template>
  <svg class="onix-icon" :width="size" :height="size" :aria-hidden="label ? undefined : true" :aria-label="label" :role="label ? 'img' : undefined">
    <use :href="`${spriteUrl}#onix-${name}`" />
  </svg>
</template>
