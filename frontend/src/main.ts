import { createApp } from "vue";
import { createPinia } from "pinia";
import PrimeVue from "primevue/config";
import ToastService from "primevue/toastservice";
import Toast from "primevue/toast";
import Message from "primevue/message";
import { definePreset } from "@primeuix/themes";
import Aura from "@primeuix/themes/aura";
import "primeicons/primeicons.css";
import "@fontsource/roboto/400.css";
import "@fontsource/roboto/500.css";
import "@fontsource/roboto/700.css";
import "@/app/styles.css";
import App from "@/app/App.vue";
import { router } from "@/app/router";

const AccountTheme = definePreset(Aura, {
  semantic: {
    primary: {
      50: "#f8fafc",
      100: "#f1f5f9",
      200: "#e2e8f0",
      300: "#cbd5e1",
      400: "#94a3b8",
      500: "#475569",
      600: "#334155",
      700: "#1e293b",
      800: "#0f172a",
      900: "#020617",
      950: "#010409",
    },
  },
});

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.use(PrimeVue, {
  theme: {
    preset: AccountTheme,
    options: { darkModeSelector: ".dark" },
  },
});
app.use(ToastService);
app.component("PToast", Toast);
app.component("PMessage", Message);
app.mount("#app");
