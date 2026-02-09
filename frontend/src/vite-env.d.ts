/// <reference types="vite/client" />

/**
 * Vue 组件类型声明
 * 告诉 TypeScript 如何处理 .vue 文件
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
