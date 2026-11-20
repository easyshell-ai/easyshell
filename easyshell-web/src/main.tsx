import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { StyleProvider } from '@ant-design/cssinjs'
import './index.css'
import App from './App'

// cssinjs 注入顺序（设计文档 §2.2-C5，拆除 index.css 中 !important 的物理基础）：
// hashPriority:'low' 让 AntD 运行时样式被 :where(.css-hash) 包裹（哈希不进特异性），
// prepend:'queue' 是 @ant-design/cssinjs@2.1.2 内置行为（useStyleRegister.js 硬编码，
// StyleProvider 无可配置项）——运行时样式落在 <head> 前部，index.css 在同特异性下按文档顺序胜出。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <StyleProvider hashPriority="low">
      <App />
    </StyleProvider>
  </StrictMode>,
)
