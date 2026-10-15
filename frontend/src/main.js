import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
// Element Plus 官方暗色基底（index.html 已挂 html.dark），再用 tokens.css 覆盖成作战大屏配色
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './styles/tokens.css';
import App from './App.vue';

const app = createApp(App);
app.use(createPinia());
app.use(ElementPlus);
app.mount('#app');
