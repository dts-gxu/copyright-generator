import type { AppRouteModule } from '/@/router/types';
import { LAYOUT } from '/@/router/constant';
import { t } from '/@/hooks/web/useI18n';

const copyright: AppRouteModule = {
  path: '/copyright',
  name: 'Copyright',
  component: LAYOUT,
  redirect: '/copyright/generator',
  meta: {
    orderNo: 15,
    icon: 'ant-design:file-protect-outlined',
    title: '基础智能体',
  },
  children: [
    {
      path: 'generator',
      name: 'CopyrightGenerator',
      component: () => import('/@/views/copyright/generator/index.vue'),
      meta: {
        title: '软著生成助手',
        icon: 'ant-design:code-outlined',
      },
    },
    {
      path: 'list',
      name: 'CopyrightList',
      component: () => import('/@/views/copyright/list/index.vue'),
      meta: {
        title: '生成记录',
        icon: 'ant-design:history-outlined',
      },
    },
  ],
};

export default copyright; 