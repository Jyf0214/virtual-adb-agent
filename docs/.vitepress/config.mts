import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/virtual-adb-agent/',
  title: 'Virtual ADB Agent',
  description: '无需 Root 的 Android ADB 代理服务',
  lang: 'zh-CN',

  themeConfig: {
    nav: [
      { text: '快速开始', link: '/guide/start' },
      {
        text: '使用指南',
        items: [
          { text: '概览', link: '/usage/' },
          { text: '基础配置', link: '/usage/configuration' },
          { text: '命令参考', link: '/usage/commands' },
          { text: '自动化集成', link: '/usage/automation' },
        ],
      },
      { text: '排错指南', link: '/troubleshoot/' },
      { text: '架构说明', link: '/architecture/' },
      {
        text: '法律',
        items: [
          { text: '用户协议', link: '/legal/terms' },
          { text: '隐私政策', link: '/legal/privacy' },
          { text: '关于', link: '/about/' },
        ],
      },
    ],

    sidebar: {
      '/usage/': [
        {
          text: '使用指南',
          items: [
            { text: '概览', link: '/usage/' },
            { text: '基础配置', link: '/usage/configuration' },
            { text: '命令参考', link: '/usage/commands' },
            { text: '自动化集成', link: '/usage/automation' },
          ],
        },
      ],
      '/legal/': [
        {
          text: '法律',
          items: [
            { text: '用户协议', link: '/legal/terms' },
            { text: '隐私政策', link: '/legal/privacy' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/Jyf0214/virtual-adb-agent' },
    ],

    footer: {
      message: '基于 AGPL-3.0 许可协议发布',
      copyright: 'Copyright 2026 Jyf0214',
    },
  },
})
