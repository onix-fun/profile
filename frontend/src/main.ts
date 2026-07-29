import { createApp } from "vue";
import { createPinia } from "pinia";
import PrimeVue from "primevue/config";
import ToastService from "primevue/toastservice";
import Toast from "primevue/toast";
import Message from "primevue/message";
import { ProfileTheme } from "@/app/primevueTheme";
import { i18n } from "@/shared/i18n";
import "@/shared/lib/preferences";
import "@/app/styles.css";
import "@onix/design-system/css";
import App from "@/app/App.vue";
import { router } from "@/app/router";

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.use(i18n);
app.use(PrimeVue, {
  theme: {
    preset: ProfileTheme,
    options: { darkModeSelector: '[data-onix-theme="dark"]' },
  },
});
app.use(ToastService);
app.component("PToast", Toast);
app.component("PMessage", Message);
app.mount("#app");
