<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { AnimationItem } from "lottie-web";
import sparkBurst from "@/assets/lottie/spark-burst.json";
import inkSplashLoop from "@/assets/lottie/ink-splash-loop.json";
import loadingBolt from "@/assets/lottie/loading-bolt.json";
import emptyCanvas from "@/assets/lottie/empty-canvas.json";

const animations = {
  "spark-burst": sparkBurst,
  "ink-splash-loop": inkSplashLoop,
  "loading-bolt": loadingBolt,
  "empty-canvas": emptyCanvas,
} as const;

type AnimationName = keyof typeof animations;

const props = withDefaults(defineProps<{
  name: AnimationName;
  loop?: boolean;
  autoplay?: boolean;
  speed?: number;
  ariaLabel?: string;
}>(), {
  loop: true,
  autoplay: true,
  speed: 1,
  ariaLabel: "",
});

const container = ref<HTMLElement | null>(null);
const reduceMotion = ref(false);
let animation: AnimationItem | null = null;
let mediaQuery: MediaQueryList | null = null;

const isDecorative = computed(() => !props.ariaLabel);
const fallbackClass = computed(() => `onix-lottie__fallback onix-lottie__fallback--${props.name}`);

onMounted(() => {
  if (navigator.userAgent.includes("jsdom")) {
    reduceMotion.value = true;
    return;
  }
  if (!window.matchMedia) {
    void renderAnimation();
    return;
  }
  mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
  reduceMotion.value = mediaQuery.matches;
  mediaQuery.addEventListener("change", onMotionPreferenceChange);
  void renderAnimation();
});

watch(() => [props.name, props.loop, props.autoplay, props.speed, reduceMotion.value] as const, () => {
  void renderAnimation();
});

onBeforeUnmount(() => {
  destroyAnimation();
  mediaQuery?.removeEventListener("change", onMotionPreferenceChange);
});

function onMotionPreferenceChange(event: MediaQueryListEvent) {
  reduceMotion.value = event.matches;
}

function destroyAnimation() {
  animation?.destroy();
  animation = null;
}

async function renderAnimation() {
  destroyAnimation();
  if (!container.value || reduceMotion.value) return;

  const { default: lottie } = await import("lottie-web");
  if (!container.value || reduceMotion.value) return;

  animation = lottie.loadAnimation({
    container: container.value,
    renderer: "svg",
    loop: props.loop,
    autoplay: props.autoplay,
    animationData: animations[props.name],
    rendererSettings: {
      progressiveLoad: true,
      preserveAspectRatio: "xMidYMid meet",
    },
  });
  animation.setSpeed(props.speed);
}
</script>

<template>
  <span
    ref="container"
    class="onix-lottie"
    :aria-hidden="isDecorative ? 'true' : undefined"
    :aria-label="ariaLabel || undefined"
    role="img"
  >
    <span v-if="reduceMotion" :class="fallbackClass"></span>
  </span>
</template>

<style scoped>
.onix-lottie {
  display: inline-block;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.onix-lottie__fallback {
  width: 100%;
  height: 100%;
  display: block;
  border: 5px solid var(--comic-ink, #05070b);
  background: var(--comic-yellow, #f6ff18);
  clip-path: polygon(50% 0, 62% 34%, 100% 38%, 68% 58%, 78% 100%, 50% 72%, 20% 100%, 30% 58%, 0 38%, 38% 34%);
  box-shadow: 8px 8px 0 var(--comic-ink, #05070b);
}

.onix-lottie__fallback--ink-splash-loop {
  background: var(--comic-lime, #14f768);
  clip-path: polygon(4% 50%, 18% 18%, 42% 26%, 58% 4%, 76% 32%, 98% 24%, 86% 58%, 96% 88%, 58% 74%, 36% 96%, 28% 66%);
}

.onix-lottie__fallback--loading-bolt {
  background: var(--comic-yellow, #f6ff18);
  clip-path: polygon(45% 0, 84% 44%, 58% 44%, 70% 100%, 16% 38%, 42% 38%);
}

.onix-lottie__fallback--empty-canvas {
  background: var(--comic-coral, #ff4f7b);
  clip-path: polygon(0 12%, 82% 12%, 100% 50%, 82% 88%, 0 88%, 10% 50%);
}
</style>
