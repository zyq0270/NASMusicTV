// 「生态地球」loader 打包入口（esbuild --bundle --format=iife --target=es5）。
//
// 产出两个全局：window.GLTFLoader / window.SkeletonUtils，
// 与演示页 `docs/globe-present.html` 的两种用法一一对应：
//   import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'      → window.GLTFLoader
//   import { clone as cloneSkeleton } from 'three/addons/utils/SkeletonUtils.js'
//                                                                        → window.SkeletonUtils.clone
//
// ⛔ three 本体不进包：`three` 由 build_loaders.mjs 的插件解析到 three-shim.mjs（只读 window.THREE）。
import { GLTFLoader } from './node_modules/three/examples/jsm/loaders/GLTFLoader.js';
import * as SkeletonUtilsModule from './node_modules/three/examples/jsm/utils/SkeletonUtils.js';

var g = typeof window !== 'undefined' ? window : globalThis;

g.GLTFLoader = GLTFLoader;
g.SkeletonUtils = {
  clone: SkeletonUtilsModule.clone,
  retarget: SkeletonUtilsModule.retarget,
  retargetClip: SkeletonUtilsModule.retargetClip
};
