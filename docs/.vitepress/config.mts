import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Virtual ADB Agent',
  description: '无需 Root 的 Android ADB 代理服务',
  lang: 'zh-CN',

  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '快速开始', link: '/guide/start' },
    ],

    sidebar: [
      {
        text: '指南',
        items: [
          { text: '快速开始', link: '/guide/start' },
        ],
      },
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/Jyf0214/virtual-adb-agent' },
    ],

    footer: {
      message: '基于 AGPL-3.0 许可协议发布',
      copyright: 'Copyright 2026 Jyf0214',
    },
  },
})
