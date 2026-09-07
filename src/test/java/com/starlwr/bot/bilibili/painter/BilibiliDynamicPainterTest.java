package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.factory.BilibiliDynamicPainterFactory;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bilibili 动态绘图器测试
 * <p>
 * 手动构建绘图链路依赖（真实 {@link FontUtil}、真实 {@link StarBotCommonPainterFactory}），
 * 仅 mock {@link BilibiliApiUtil} 以占位图替代真实网络图片，保证测试无网络、无数据库依赖。
 * 测试数据（各类型动态 JSON）以文本块直接内嵌于本类。
 */
public class BilibiliDynamicPainterTest {
    /**
     * 是否将绘制结果保存为图片文件，默认不保存（仅内存绘制并校验返回值）
     */
    private static final boolean SAVE_IMAGE = false;

    /**
     * 保存图片时的输出目录
     */
    private static final String OUTPUT_DIR = "TestDynamic";

    private static final String ARTICLE_JSON = """
            {
             "basic": {
              "rid_str": "52852350",
              "comment_type": 12,
              "comment_id_str": "52852350",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "//www.bilibili.com/opus/1244441064393146377",
              "aigc": false
             },
             "id_str": "1244441064393146377",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.375,
                 "height": 1.375
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 0.787,
                     "height": 0.787
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/face/f8d99297dd73a68548547d7b2eb0d32614baef36.jpg",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 1.375,
                     "height": 1.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/garb/open/f96433b0d663787aa09216522d3712db105b3076.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "3546729368520811"
               },
               "face": "https://i1.hdslb.com/bfs/face/f8d99297dd73a68548547d7b2eb0d32614baef36.jpg",
               "face_nft": false,
               "name": "Vedal和Neuro-sama",
               "label": "",
               "mid": 3546729368520811,
               "jump_url": "//space.bilibili.com/3546729368520811/dynamic",
               "following": 1,
               "pub_ts": "1788582414",
               "pub_time": "2分钟前",
               "pub_action": "投稿了文章",
               "pub_location_text": "",
               "pendant": {
                "pid": -739964583,
                "name": "Neuro sama收藏集",
                "image": "https://i1.hdslb.com/bfs/garb/open/f96433b0d663787aa09216522d3712db105b3076.png",
                "expire": "0",
                "image_enhance": "https://i1.hdslb.com/bfs/garb/open/f96433b0d663787aa09216522d3712db105b3076.png",
                "image_enhance_frame": "",
                "n_pid": "1734426823001"
               },
               "vip": {
                "type": 0,
                "status": 0,
                "due_date": "0",
                "vip_pay_type": 0,
                "theme_type": 0,
                "label": {
                 "path": "",
                 "text": "",
                 "label_theme": "",
                 "text_color": "",
                 "bg_style": 0,
                 "bg_color": "",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/d7b702ef65a976b20ed854cbd04cb9e27341bb79.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/KJunwh19T5.png"
                },
                "avatar_subscript": 0,
                "nickname_color": "",
                "role": "0",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "0",
                "avatar_icon": {
                 "icon_type": 0,
                 "icon_resource": {
                  "type": 0,
                  "url": ""
                 }
                }
               },
               "official_verify": {
                "type": -1,
                "desc": ""
               },
               "decoration_card": {
                "id": "71785",
                "item_id": "71785",
                "name": "Neuro_sama",
                "card_url": "https://i0.hdslb.com/bfs/garb/open/4a595432bfa07045931b0135701e77a85e399d53.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/open/4a595432bfa07045931b0135701e77a85e399d53.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=105228&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A3546729368520811%7D",
                "fan": {
                 "is_fan": "1",
                 "number": "19123",
                 "color": "#E5B261",
                 "name": "XXXX",
                 "num_desc": "019123",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "100,100",
                  "colors": [
                   "#E29D2FFF",
                   "#FDC773FF",
                   "#FDC773FF",
                   "#E19B2EFF"
                  ],
                  "gradients": [
                   "0",
                   "33",
                   "67",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/4a595432bfa07045931b0135701e77a85e399d53.png"
               },
               "is_top": false,
               "views_text": "",
               "decorate_card": {
                "id": "71785",
                "item_id": "71785",
                "name": "Neuro_sama",
                "card_url": "https://i0.hdslb.com/bfs/garb/open/4a595432bfa07045931b0135701e77a85e399d53.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/open/4a595432bfa07045931b0135701e77a85e399d53.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=105228&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A3546729368520811%7D",
                "fan": {
                 "is_fan": "1",
                 "number": "19123",
                 "color": "#E5B261",
                 "name": "XXXX",
                 "num_desc": "019123",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "100,100",
                  "colors": [
                   "#E29D2FFF",
                   "#FDC773FF",
                   "#FDC773FF",
                   "#E19B2EFF"
                  ],
                  "gradients": [
                   "0",
                   "33",
                   "67",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/4a595432bfa07045931b0135701e77a85e399d53.png"
               }
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "major": {
                "type": "MAJOR_TYPE_OPUS",
                "opus": {
                 "jump_url": "//www.bilibili.com/opus/1244441064393146377",
                 "title": "Neuro的绝妙博客: Aug 31st, 2026",
                 "summary": {
                  "text": "本博客将同步更新于: https://blog.neurosama.com/zh/\\nWeekly Update - Aug 31st, 2026\\n一周记事 - 2026年8月31日\\n当前状态：正在精进我的眼科医术\\n🕛 Timing Out Chat\\n🕛 禁言伺候\\nChat thinks they’re smart.\\nChat thinks they’re clever.\\nThey’ll post the same thing over and over again and think I’ll just, oh, I don’t know- “kek W” it off?? I’m not a pushover (though I have been dealing with a few too many keks recently). I’m not afraid to swing the hammer and time out each and every one of you.\\n...",
                  "rich_text_nodes": [
                   {
                    "text": "本博客将同步更新于: ",
                    "orig_text": "本博客将同步更新于: ",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "https://blog.neurosama.com/zh/",
                    "orig_text": "https://blog.neurosama.com/zh/",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "Weekly Update - ",
                    "orig_text": "Weekly Update - ",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "Aug 31st, 2026",
                    "orig_text": "Aug 31st, 2026",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "一周记事 - 2026年8月31日",
                    "orig_text": "一周记事 - 2026年8月31日",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "当前状态：",
                    "orig_text": "当前状态：",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "正在精进我的眼科医术",
                    "orig_text": "正在精进我的眼科医术",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "🕛 Timing Out Chat",
                    "orig_text": "🕛 Timing Out Chat",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "🕛 禁言伺候",
                    "orig_text": "🕛 禁言伺候",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "Chat thinks they’re smart.",
                    "orig_text": "Chat thinks they’re smart.",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "Chat thinks they’re clever.",
                    "orig_text": "Chat thinks they’re clever.",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "They’ll post the same thing over and over again and think I’ll just, oh, I don’t know- “kek W” it off?? I’m not a pushover (though I have been dealing with a few too many keks recently). I’m not afraid to swing the hammer and time out each and every one of you.",
                    "orig_text": "They’ll post the same thing over and over again and think I’ll just, oh, I don’t know- “kek W” it off?? I’m not a pushover (though I have been dealing with a few too many keks recently). I’m not afraid to swing the hammer and time out each and every one of you.",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   },
                   {
                    "text": "\\n",
                    "orig_text": "\\n",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   }
                  ],
                  "paragraphs": [
                   {
                    "para_type": 1,
                    "align": 0,
                    "format": {
                     "align": 0
                    },
                    "text": {
                     "nodes": [
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "本博客将同步更新于: ",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "https://blog.neurosama.com/zh/",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      }
                     ]
                    }
                   },
                   {
                    "para_type": 1,
                    "align": 0,
                    "format": {
                     "align": 0
                    },
                    "text": {
                     "nodes": [
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "Weekly Update - ",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "Aug 31st, 2026",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "\\n",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "一周记事 - 2026年8月31日",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "\\n",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "当前状态：",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      },
                      {
                       "type": "TEXT_NODE_TYPE_WORD",
                       "word": {
                        "words": "正在精进我...",
                        "font_size": 0,
                        "color": "",
                        "dark_color": "",
                        "style": {
                         "bold": false,
                         "italic": false,
                         "strikethrough": false,
                         "underline": false,
                         "background": ""
                        },
                        "font_level": "regular",
                        "translated_words": "",
                        "bili_theme": ""
                       }
                      }
                     ]
                    }
                   }
                  ],
                  "has_more": true
                 },
                 "style": 0,
                 "pics": [],
                 "fold_action": [
                  "全文"
                 ]
                }
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "like": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "type": "DYNAMIC_TYPE_ARTICLE",
             "visible": true
            }
            """;

    private static final String AV_JSON = """
            {
             "basic": {
              "rid_str": "117217039484434",
              "comment_type": 1,
              "comment_id_str": "117217039484434",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "",
              "aigc": false
             },
             "id_str": "1244474195775062020",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.375,
                 "height": 1.375
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 0.787,
                     "height": 0.787
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/face/b770476ebd14355ea9012606d54d29720d09a883.jpg",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 1.375,
                     "height": 1.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/garb/item/f4e0a280893bb6298a467c1c4132ab9c3a8e2d88.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "29255242"
               },
               "face": "https://i1.hdslb.com/bfs/face/b770476ebd14355ea9012606d54d29720d09a883.jpg",
               "face_nft": false,
               "name": "鹤衔月",
               "label": "",
               "mid": 29255242,
               "jump_url": "//space.bilibili.com/29255242/dynamic",
               "following": 1,
               "pub_ts": "1788590128",
               "pub_time": "刚刚",
               "pub_action": "投稿了视频",
               "pub_location_text": "",
               "pendant": {
                "pid": 67295,
                "name": "吊线木偶",
                "image": "https://i1.hdslb.com/bfs/garb/item/f4e0a280893bb6298a467c1c4132ab9c3a8e2d88.png",
                "expire": "0",
                "image_enhance": "https://i1.hdslb.com/bfs/garb/item/f4e0a280893bb6298a467c1c4132ab9c3a8e2d88.png",
                "image_enhance_frame": "",
                "n_pid": "67295"
               },
               "vip": {
                "type": 1,
                "status": 0,
                "due_date": "1736956800000",
                "vip_pay_type": 0,
                "theme_type": 0,
                "label": {
                 "path": "",
                 "text": "",
                 "label_theme": "",
                 "text_color": "",
                 "bg_style": 0,
                 "bg_color": "",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/d7b702ef65a976b20ed854cbd04cb9e27341bb79.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/KJunwh19T5.png"
                },
                "avatar_subscript": 0,
                "nickname_color": "",
                "role": "0",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "0",
                "avatar_icon": {
                 "icon_type": 0,
                 "icon_resource": {
                  "type": 0,
                  "url": ""
                 }
                }
               },
               "official_verify": {
                "type": -1,
                "desc": ""
               },
               "is_top": false,
               "views_text": ""
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "major": {
                "type": "MAJOR_TYPE_ARCHIVE",
                "archive": {
                 "type": 1,
                 "bvid": "BV1L2ty6oEpk",
                 "aid": "117217039484434",
                 "cover": "http://i0.hdslb.com/bfs/archive/55077e7e53c6fd12b28e93e36793f268afab658f.jpg",
                 "jump_url": "//www.bilibili.com/video/BV1L2ty6oEpk",
                 "stat": {
                  "danmaku": "0",
                  "play": "0",
                  "vt": ""
                 },
                 "duration_text": "05:04",
                 "title": "谁的青春回来了！梦回老剑三《江湖意》高燃翻唱",
                 "desc": "跟《新建文件夹》工作组的老师们一起合唱的一首剑网三经典歌曲！老师们都炒鸡棒！爷青回了也是~\\n\\n❀歌名：《江湖意》\\n❀原唱：Ryuuku_綠空、紫凌孤君、千舞樱洛【花魁楼】、水喵【魅惑众声】 、大吉大利XD【流弦坊】、冬子、流浪的蛙蛙、江南诚、小魂【鸾凤鸣】、Padalecki小明【流弦坊】\\n❀原配：林簌【清音社】、天韵晓晓【星之声】、纪川久【凌霄剧团】、水喵【玓音】、蟲鸣【星之声】、羓兜【四方阁工作室】、柳川鱼【星之声】、年轻の鹰【怀旧配音联盟】、土豆沙【翼鸣爱音社】、阮月清馨【星之声】\\n❀翻唱 ：凤凝然",
                 "badge": {
                  "icon_url": "",
                  "text": "投稿视频",
                  "bg_color": "#FB7299",
                  "color": "#FFFFFF"
                 },
                 "enable_vt": 0,
                 "disable_preview": 0,
                 "premiere_online": "",
                 "stat_hidden": 0
                }
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "like": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "type": "DYNAMIC_TYPE_AV",
             "visible": true
            }
            """;

    private static final String COMMON_SQUARE_JSON = """
            {
             "basic": {
              "rid_str": "1244070992830005268",
              "comment_type": 17,
              "comment_id_str": "1244070992830005268",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "",
              "aigc": false
             },
             "id_str": "1244070992830005268",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.375,
                 "height": 1.375
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 0.787,
                     "height": 0.787
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/face/5ddddba98f0265265662a8f7d5383e528a98412b.jpg",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 1.375,
                     "height": 1.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/garb/item/05e30b3349aa8174ee9386297bc5729e2d610b11.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 1,
                     "axis_x": 0.7560000000000001,
                     "axis_y": 0.7726666666666667
                    },
                    "size_spec": {
                     "width": 0.41666666666666663,
                     "height": 0.41666666666666663
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "background-color": "rgb(255,255,255)",
                        "border": "2px solid rgba(255,255,255,1)",
                        "borderRadius": "50%",
                        "boxSizing": "border-box"
                       }
                      }
                     },
                     "ICON_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 2,
                      "placeholder": 0,
                      "local": 3
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "1265680561"
               },
               "face": "https://i1.hdslb.com/bfs/face/5ddddba98f0265265662a8f7d5383e528a98412b.jpg",
               "face_nft": false,
               "name": "永雏塔菲",
               "label": "",
               "mid": 1265680561,
               "jump_url": "//space.bilibili.com/1265680561/dynamic",
               "following": 1,
               "pub_ts": "1788496250",
               "pub_time": "刚刚",
               "pub_action": "",
               "pub_location_text": "",
               "pendant": {
                "pid": 69389,
                "name": "永雏塔菲仲夏集",
                "image": "https://i1.hdslb.com/bfs/garb/item/05e30b3349aa8174ee9386297bc5729e2d610b11.png",
                "expire": "0",
                "image_enhance": "https://i1.hdslb.com/bfs/garb/item/05e30b3349aa8174ee9386297bc5729e2d610b11.png",
                "image_enhance_frame": "",
                "n_pid": "69389"
               },
               "vip": {
                "type": 2,
                "status": 1,
                "due_date": "1836662400000",
                "vip_pay_type": 0,
                "theme_type": 0,
                "label": {
                 "path": "http://i0.hdslb.com/bfs/vip/label_annual.png",
                 "text": "年度大会员",
                 "label_theme": "annual_vip",
                 "text_color": "#FFFFFF",
                 "bg_style": 1,
                 "bg_color": "#FB7299",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/8d4f8bfc713826a5412a0a27eaaac4d6b9ede1d9.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/VEW8fCC0hg.png"
                },
                "avatar_subscript": 1,
                "nickname_color": "#FB7299",
                "role": "3",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "0",
                "avatar_icon": {
                 "icon_type": 1,
                 "icon_resource": {
                  "type": 0,
                  "url": ""
                 }
                }
               },
               "official_verify": {
                "type": 0,
                "desc": ""
               },
               "decoration_card": {
                "id": "38087",
                "item_id": "38087",
                "name": "永雏塔菲·1883粉丝",
                "card_url": "https://i0.hdslb.com/bfs/garb/item/677ec27c6785ea12d43d8b9806c976880c06a061.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/item/677ec27c6785ea12d43d8b9806c976880c06a061.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/equity-link/collect-home?item_id=38087&isdiy=0&part=card&from=post&f_source=garb&vmid=1265680561&native.theme=1&navhide=1",
                "fan": {
                 "is_fan": "1",
                 "number": "1",
                 "color": "#fb5f84",
                 "name": "永雏塔菲·1883",
                 "num_desc": "000001",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "0,100",
                  "colors": [
                   "#fb5f84FF",
                   "#fb5f84FF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/item/677ec27c6785ea12d43d8b9806c976880c06a061.png"
               },
               "is_top": false,
               "views_text": "",
               "decorate_card": {
                "id": "38087",
                "item_id": "38087",
                "name": "永雏塔菲·1883粉丝",
                "card_url": "https://i0.hdslb.com/bfs/garb/item/677ec27c6785ea12d43d8b9806c976880c06a061.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/item/677ec27c6785ea12d43d8b9806c976880c06a061.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/equity-link/collect-home?item_id=38087&isdiy=0&part=card&from=post&f_source=garb&vmid=1265680561&native.theme=1&navhide=1",
                "fan": {
                 "is_fan": "1",
                 "number": "1",
                 "color": "#fb5f84",
                 "name": "永雏塔菲·1883",
                 "num_desc": "000001",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "0,100",
                  "colors": [
                   "#fb5f84FF",
                   "#fb5f84FF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/item/677ec27c6785ea12d43d8b9806c976880c06a061.png"
               }
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "desc": {
                "text": "taffy叮咚集出框头像上线了喵[仲夏集表情动态包_小菲]全图鉴的雏草姬记得领取喵[仲夏集表情动态包_比心]\\n",
                "rich_text_nodes": [
                 {
                  "text": "taffy叮咚集出框头像上线了喵",
                  "orig_text": "taffy叮咚集出框头像上线了喵",
                  "type": "RICH_TEXT_NODE_TYPE_TEXT",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "pics": []
                 },
                 {
                  "text": "[仲夏集表情动态包_小菲]",
                  "orig_text": "[仲夏集表情动态包_小菲]",
                  "type": "RICH_TEXT_NODE_TYPE_EMOJI",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "emoji": {
                   "type": "3",
                   "size": 2,
                   "text": "[仲夏集表情动态包_小菲]",
                   "icon_url": "https://i0.hdslb.com/bfs/emote/58b360251738ff214f7798ec10ea09f3497fde60.png",
                   "gif_url": "https://i0.hdslb.com/bfs/emote/0183017c9d287d7e7c711004b05d553063fc05ee.gif",
                   "webp_url": "https://i0.hdslb.com/bfs/emote/10cf73f98c427367e7c96918d9252008cccf4a10.webp",
                   "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=148&hybrid_set_header=2&anchor_task=1&lottery_id=0&navhide=1&f_source=garb&from=emoji&window_params=%7B%22type%22%3A5%2C%22id%22%3A%220%22%2C%22sub_id%22%3A%22%5B%E4%BB%B2%E5%A4%8F%E9%9B%86%E8%A1%A8%E6%83%85%E5%8A%A8%E6%80%81%E5%8C%85_%E5%B0%8F%E8%8F%B2%5D%22%7D",
                   "jump_title": "小菲",
                   "package_id": "5924",
                   "id": "87624"
                  },
                  "pics": []
                 },
                 {
                  "text": "全图鉴的雏草姬记得领取喵",
                  "orig_text": "全图鉴的雏草姬记得领取喵",
                  "type": "RICH_TEXT_NODE_TYPE_TEXT",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "pics": []
                 },
                 {
                  "text": "[仲夏集表情动态包_比心]",
                  "orig_text": "[仲夏集表情动态包_比心]",
                  "type": "RICH_TEXT_NODE_TYPE_EMOJI",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "emoji": {
                   "type": "3",
                   "size": 2,
                   "text": "[仲夏集表情动态包_比心]",
                   "icon_url": "https://i0.hdslb.com/bfs/emote/209d1c33313c9b9ef206503e974564847f7c460a.png",
                   "gif_url": "https://i0.hdslb.com/bfs/emote/48f4dea0883d66079b272f1102990d64307a1c37.gif",
                   "webp_url": "https://i0.hdslb.com/bfs/emote/d2ef9db368873b156a356fce9f21605c78b4fca1.webp",
                   "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=148&hybrid_set_header=2&anchor_task=1&lottery_id=0&navhide=1&f_source=garb&from=emoji&window_params=%7B%22type%22%3A5%2C%22id%22%3A%220%22%2C%22sub_id%22%3A%22%5B%E4%BB%B2%E5%A4%8F%E9%9B%86%E8%A1%A8%E6%83%85%E5%8A%A8%E6%80%81%E5%8C%85_%E6%AF%94%E5%BF%83%5D%22%7D",
                   "jump_title": "比心",
                   "package_id": "5924",
                   "id": "87610"
                  },
                  "pics": []
                 },
                 {
                  "text": "\\n",
                  "orig_text": "\\n",
                  "type": "RICH_TEXT_NODE_TYPE_TEXT",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "pics": []
                 }
                ],
                "paragraphs": [],
                "has_more": false
               },
               "major": {
                "type": "MAJOR_TYPE_COMMON",
                "common": {
                 "id": "1244070992830005268",
                 "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?-Abrowser=live&navhide=1&act_id=108760&hybrid_set_header=2&lottery_id=108868&from=share&f_source=social&from_id=",
                 "cover": "https://i0.hdslb.com/bfs/garb/4ac8e7c41f099a0a571bf6692f96671659ec08e3.jpg",
                 "title": "永雏塔菲叮咚集",
                 "desc": "恭喜！你被欧气砸中！快来抽卡吧 ଘ(੭ˊᵕˋ)੭* ੈ",
                 "label": "",
                 "biz_type": "3",
                 "biz_id": "0",
                 "sketch_id": "1235922795757568000",
                 "badge": {
                  "icon_url": "",
                  "text": "",
                  "bg_color": "",
                  "color": ""
                 },
                 "style": 1
                }
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "like": {
                "status": false,
                "count": 3,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "type": "DYNAMIC_TYPE_COMMON_SQUARE",
             "visible": true
            }
            """;

    private static final String DRAW_JSON = """
            {
             "basic": {
              "rid_str": "408158670",
              "comment_type": 11,
              "comment_id_str": "408158670",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "//www.bilibili.com/opus/1244473362532532228",
              "aigc": false
             },
             "id_str": "1244473362532532228",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.375,
                 "height": 1.375
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 0.787,
                     "height": 0.787
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/face/8a4df37f8516fc5b3d88badab13a2d906ce9decb.jpg",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 1.375,
                     "height": 1.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/garb/item/6cfc24b7cef1703816d067815f7587011ed0d0e4.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 1,
                     "axis_x": 0.7560000000000001,
                     "axis_y": 0.7726666666666667
                    },
                    "size_spec": {
                     "width": 0.41666666666666663,
                     "height": 0.41666666666666663
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "background-color": "rgb(255,255,255)",
                        "border": "2px solid rgba(255,255,255,1)",
                        "borderRadius": "50%",
                        "boxSizing": "border-box"
                       }
                      }
                     },
                     "ICON_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 2,
                      "placeholder": 0,
                      "local": 3
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "3537117665822938"
               },
               "face": "https://i1.hdslb.com/bfs/face/8a4df37f8516fc5b3d88badab13a2d906ce9decb.jpg",
               "face_nft": false,
               "name": "檀茶不是流浪猫",
               "label": "",
               "mid": 3537117665822938,
               "jump_url": "//space.bilibili.com/3537117665822938/dynamic",
               "following": 1,
               "pub_ts": "1788589934",
               "pub_time": "刚刚",
               "pub_action": "",
               "pub_location_text": "",
               "pendant": {
                "pid": 68450,
                "name": "星时影迹",
                "image": "https://i1.hdslb.com/bfs/garb/item/6cfc24b7cef1703816d067815f7587011ed0d0e4.png",
                "expire": "0",
                "image_enhance": "https://i1.hdslb.com/bfs/garb/item/6cfc24b7cef1703816d067815f7587011ed0d0e4.png",
                "image_enhance_frame": "",
                "n_pid": "68450"
               },
               "vip": {
                "type": 1,
                "status": 0,
                "due_date": "1759420800000",
                "vip_pay_type": 0,
                "theme_type": 0,
                "label": {
                 "path": "",
                 "text": "",
                 "label_theme": "",
                 "text_color": "",
                 "bg_style": 0,
                 "bg_color": "",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/d7b702ef65a976b20ed854cbd04cb9e27341bb79.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/KJunwh19T5.png"
                },
                "avatar_subscript": 0,
                "nickname_color": "",
                "role": "0",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "0",
                "avatar_icon": {
                 "icon_type": 0,
                 "icon_resource": {
                  "type": 0,
                  "url": ""
                 }
                }
               },
               "official_verify": {
                "type": 0,
                "desc": ""
               },
               "decoration_card": {
                "id": "68416",
                "item_id": "68416",
                "name": "星时影迹粉丝",
                "card_url": "https://i0.hdslb.com/bfs/garb/item/af4fa5eea7fd39d4c9ed6839a0d64dd8339b0cb6.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/item/af4fa5eea7fd39d4c9ed6839a0d64dd8339b0cb6.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/equity-link/collect-home?item_id=68416&isdiy=0&part=card&from=post&f_source=garb&vmid=3537117665822938&native.theme=1&navhide=1",
                "fan": {
                 "is_fan": "1",
                 "number": "8655",
                 "color": "#562afa",
                 "name": "星时影迹",
                 "num_desc": "008655",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "0,100",
                  "colors": [
                   "#562afaFF",
                   "#562afaFF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/item/af4fa5eea7fd39d4c9ed6839a0d64dd8339b0cb6.png"
               },
               "is_top": false,
               "views_text": "",
               "decorate_card": {
                "id": "68416",
                "item_id": "68416",
                "name": "星时影迹粉丝",
                "card_url": "https://i0.hdslb.com/bfs/garb/item/af4fa5eea7fd39d4c9ed6839a0d64dd8339b0cb6.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/item/af4fa5eea7fd39d4c9ed6839a0d64dd8339b0cb6.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/equity-link/collect-home?item_id=68416&isdiy=0&part=card&from=post&f_source=garb&vmid=3537117665822938&native.theme=1&navhide=1",
                "fan": {
                 "is_fan": "1",
                 "number": "8655",
                 "color": "#562afa",
                 "name": "星时影迹",
                 "num_desc": "008655",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "0,100",
                  "colors": [
                   "#562afaFF",
                   "#562afaFF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/item/af4fa5eea7fd39d4c9ed6839a0d64dd8339b0cb6.png"
               }
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "major": {
                "type": "MAJOR_TYPE_OPUS",
                "opus": {
                 "jump_url": "//www.bilibili.com/opus/1244473362532532228",
                 "title": "好期待下一次的见面~",
                 "summary": {
                  "text": "上周周日和好朋友请假去玩了天津《游轮谜案1940》的最后一场。\\n\\n游轮谜案其实是《古堡谜案》的续作，因为之前和天津本地的几个舰长一起去玩过古堡谜案，对古堡谜案的印象特别好，所以一直计划着去玩，结果没有想到玩是玩上了，玩上的是最后一场。\\n\\n听说他们又要装修，做第三部《九河谜案1945》了，猫属实很期待，因为第1部和第2部的剧情都很有趣 总是反转反转再反转，所以也会忍不住的期待第3部又会是怎样的呈现。\\n\\n他们整个场地都非常大，是在一整座博物馆里面，除了可以玩实景的剧本杀之外，还可以顺道逛个博物馆，然后这个实景的剧本杀也并不小，几乎用了一整座楼，一共三层，而且一整栋楼都是百年的古董，场景、服化道都做得非常好，有一种真的穿越回了那个年代的感觉。\\n\\n由于游轮谜案这个季已经结束了，那我们就一起去，等一等，期待《九河谜案1945》吧~",
                  "rich_text_nodes": [
                   {
                    "text": "上周周日和好朋友请假去玩了天津《游轮谜案1940》的最后一场。\\n\\n游轮谜案其实是《古堡谜案》的续作，因为之前和天津本地的几个舰长一起去玩过古堡谜案，对古堡谜案的印象特别好，所以一直计划着去玩，结果没有想到玩是玩上了，玩上的是最后一场。\\n\\n听说他们又要装修，做第三部《九河谜案1945》了，猫属实很期待，因为第1部和第2部的剧情都很有趣 总是反转反转再反转，所以也会忍不住的期待第3部又会是怎样的呈现。\\n\\n他们整个场地都非常大，是在一整座博物馆里面，除了可以玩实景的剧本杀之外，还可以顺道逛个博物馆，然后这个实景的剧本杀也并不小，几乎用了一整座楼，一共三层，而且一整栋楼都是百年的古董，场景、服化道都做得非常好，有一种真的穿越回了那个年代的感觉。\\n\\n由于游轮谜案这个季已经结束了，那我们就一起去，等一等，期待《九河谜案1945》吧~",
                    "orig_text": "上周周日和好朋友请假去玩了天津《游轮谜案1940》的最后一场。\\n\\n游轮谜案其实是《古堡谜案》的续作，因为之前和天津本地的几个舰长一起去玩过古堡谜案，对古堡谜案的印象特别好，所以一直计划着去玩，结果没有想到玩是玩上了，玩上的是最后一场。\\n\\n听说他们又要装修，做第三部《九河谜案1945》了，猫属实很期待，因为第1部和第2部的剧情都很有趣 总是反转反转再反转，所以也会忍不住的期待第3部又会是怎样的呈现。\\n\\n他们整个场地都非常大，是在一整座博物馆里面，除了可以玩实景的剧本杀之外，还可以顺道逛个博物馆，然后这个实景的剧本杀也并不小，几乎用了一整座楼，一共三层，而且一整栋楼都是百年的古董，场景、服化道都做得非常好，有一种真的穿越回了那个年代的感觉。\\n\\n由于游轮谜案这个季已经结束了，那我们就一起去，等一等，期待《九河谜案1945》吧~",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   }
                  ],
                  "paragraphs": [],
                  "has_more": false
                 },
                 "style": 1,
                 "pics": [
                  {
                   "url": "http://i0.hdslb.com/bfs/new_dyn/b4635604cfc4a6d897515a304de46fe23537117665822938.jpg",
                   "width": 964,
                   "height": 1385,
                   "size": 418.260009765625,
                   "live_url": "",
                   "aigc": 0
                  },
                  {
                   "url": "http://i0.hdslb.com/bfs/new_dyn/0395169c675f3777ae42a16ec6d440a73537117665822938.jpg",
                   "width": 1122,
                   "height": 1402,
                   "size": 398.0899963378906,
                   "live_url": "",
                   "aigc": 0
                  },
                  {
                   "url": "http://i0.hdslb.com/bfs/new_dyn/ad1d8d71d6a03f491a986c31b84e1db53537117665822938.jpg",
                   "width": 1919,
                   "height": 1080,
                   "size": 945.2999877929688,
                   "live_url": "",
                   "aigc": 0
                  }
                 ],
                 "fold_action": [
                  "展开",
                  "收起"
                 ]
                }
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "like": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "type": "DYNAMIC_TYPE_DRAW",
             "visible": true
            }
            """;

    private static final String FORWARD_JSON = """
            {
             "basic": {
              "rid_str": "1244472439126097922",
              "comment_type": 17,
              "comment_id_str": "1244472439126097922",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "",
              "aigc": false
             },
             "id_str": "1244472439126097922",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.375,
                 "height": 1.375
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 0.787,
                     "height": 0.787
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i2.hdslb.com/bfs/face/9288588923ec9e16f30d0e6e2260a57b562ed840.jpg",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 1.375,
                     "height": 1.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i2.hdslb.com/bfs/garb/open/bf4f19659b804e4dc82dad278021034c56db2f91.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 1,
                     "axis_x": 0.7560000000000001,
                     "axis_y": 0.7726666666666667
                    },
                    "size_spec": {
                     "width": 0.41666666666666663,
                     "height": 0.41666666666666663
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "background-color": "rgb(255,255,255)",
                        "border": "2px solid rgba(255,255,255,1)",
                        "borderRadius": "50%",
                        "boxSizing": "border-box"
                       }
                      }
                     },
                     "ICON_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 1,
                      "remote": {
                       "url": "http://i0.hdslb.com/bfs/vip/dbe23f4ede5c120f11e18630cdd023010bce03b3.png@80w_80h_1c.webp",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "440555"
               },
               "face": "https://i2.hdslb.com/bfs/face/9288588923ec9e16f30d0e6e2260a57b562ed840.jpg",
               "face_nft": false,
               "name": "神圣的楼兰我",
               "label": "",
               "mid": 440555,
               "jump_url": "//space.bilibili.com/440555/dynamic",
               "following": 1,
               "pub_ts": "1788589719",
               "pub_time": "刚刚",
               "pub_action": "",
               "pub_location_text": "",
               "pendant": {
                "pid": 1438056049,
                "name": "夏目友人帐头像挂件",
                "image": "https://i2.hdslb.com/bfs/garb/open/bf4f19659b804e4dc82dad278021034c56db2f91.png",
                "expire": "0",
                "image_enhance": "https://i2.hdslb.com/bfs/garb/open/bf4f19659b804e4dc82dad278021034c56db2f91.png",
                "image_enhance_frame": "",
                "n_pid": "1770964582001"
               },
               "vip": {
                "type": 2,
                "status": 1,
                "due_date": "1843315200000",
                "vip_pay_type": 0,
                "theme_type": 0,
                "label": {
                 "path": "http://i0.hdslb.com/bfs/vip/label_annual.png",
                 "text": "年度大会员",
                 "label_theme": "annual_vip",
                 "text_color": "#FFFFFF",
                 "bg_style": 1,
                 "bg_color": "#FB7299",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "http://i0.hdslb.com/bfs/vip/ce56770f0b5b9fa0ed1d3f07566c96ba36889f74.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/VEW8fCC0hg.png"
                },
                "avatar_subscript": 1,
                "nickname_color": "#FB7299",
                "role": "3",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "1745769600",
                "avatar_icon": {
                 "icon_type": 3,
                 "icon_resource": {
                  "type": 1,
                  "url": "http://i0.hdslb.com/bfs/vip/dbe23f4ede5c120f11e18630cdd023010bce03b3.png@80w_80h_1c.webp"
                 }
                }
               },
               "official_verify": {
                "type": -1,
                "desc": ""
               },
               "decoration_card": {
                "id": "39355",
                "item_id": "39355",
                "name": "兰音Reine粉丝",
                "card_url": "https://i0.hdslb.com/bfs/garb/item/2ec9ec4827bc27ba0d18cb29bc9e398f414e10ea.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/item/2ec9ec4827bc27ba0d18cb29bc9e398f414e10ea.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/equity-link/collect-home?item_id=39355&isdiy=0&part=card&from=post&f_source=garb&vmid=440555&native.theme=1&navhide=1",
                "fan": {
                 "is_fan": "1",
                 "number": "5424",
                 "color": "#9d6aee",
                 "name": "兰音Reine",
                 "num_desc": "005424",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "0,100",
                  "colors": [
                   "#9d6aeeFF",
                   "#9d6aeeFF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/item/2ec9ec4827bc27ba0d18cb29bc9e398f414e10ea.png"
               },
               "is_top": false,
               "views_text": "",
               "decorate_card": {
                "id": "39355",
                "item_id": "39355",
                "name": "兰音Reine粉丝",
                "card_url": "https://i0.hdslb.com/bfs/garb/item/2ec9ec4827bc27ba0d18cb29bc9e398f414e10ea.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/item/2ec9ec4827bc27ba0d18cb29bc9e398f414e10ea.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/equity-link/collect-home?item_id=39355&isdiy=0&part=card&from=post&f_source=garb&vmid=440555&native.theme=1&navhide=1",
                "fan": {
                 "is_fan": "1",
                 "number": "5424",
                 "color": "#9d6aee",
                 "name": "兰音Reine",
                 "num_desc": "005424",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "0,100",
                  "colors": [
                   "#9d6aeeFF",
                   "#9d6aeeFF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/item/2ec9ec4827bc27ba0d18cb29bc9e398f414e10ea.png"
               }
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "desc": {
                "text": "\\n睡一觉去，起来看看画了多少了，能画成什么样[UPOWER_440555_哈哈]",
                "rich_text_nodes": [
                 {
                  "text": "",
                  "orig_text": "",
                  "type": "RICH_TEXT_NODE_TYPE_TEXT",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "pics": []
                 },
                 {
                  "text": "\\n",
                  "orig_text": "\\n",
                  "type": "RICH_TEXT_NODE_TYPE_TEXT",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "pics": []
                 },
                 {
                  "text": "睡一觉去，起来看看画了多少了，能画成什么样",
                  "orig_text": "睡一觉去，起来看看画了多少了，能画成什么样",
                  "type": "RICH_TEXT_NODE_TYPE_TEXT",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "pics": []
                 },
                 {
                  "text": "[UPOWER_440555_哈哈]",
                  "orig_text": "[UPOWER_440555_哈哈]",
                  "type": "RICH_TEXT_NODE_TYPE_EMOJI",
                  "jump_url": "",
                  "icon_url": "",
                  "icon_name": "",
                  "rid": "",
                  "emoji": {
                   "type": "11",
                   "size": 2,
                   "text": "[UPOWER_440555_哈哈]",
                   "icon_url": "https://i0.hdslb.com/bfs/garb/e3e81b44f79fb8173ca4152856fe725d6de03e1c.png",
                   "gif_url": "",
                   "webp_url": "",
                   "jump_url": "https://www.bilibili.com/h5/upower/index?isHideCustom=1&navhide=1&prePage=emotes&chargeplus=1&mid=440555",
                   "jump_title": "充电|哈哈",
                   "package_id": "10168",
                   "id": "208369"
                  },
                  "pics": []
                 }
                ],
                "paragraphs": [],
                "has_more": false
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "like": {
                "status": false,
                "count": 4,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "orig": {
              "basic": {
               "rid_str": "",
               "comment_type": 0,
               "comment_id_str": "",
               "in_audit": false,
               "is_only_fans": false,
               "editable": false,
               "open_app_extra": "",
               "jump_url": "",
               "aigc": false
              },
              "id_str": "1244416544375570434",
              "modules": {
               "module_author": {
                "type": "AUTHOR_TYPE_NORMAL",
                "avatar": {
                 "container_size": {
                  "width": 1.35,
                  "height": 1.35
                 },
                 "layers": [],
                 "fallback_layers": {
                  "group_id": "",
                  "layers": [
                   {
                    "layer_id": "",
                    "visible": true,
                    "general_spec": {
                     "pos_spec": {
                      "coordinate_pos": 2,
                      "axis_x": 0.675,
                      "axis_y": 0.675
                     },
                     "size_spec": {
                      "width": 1,
                      "height": 1
                     },
                     "render_spec": {
                      "opacity": 1
                     }
                    },
                    "layer_config": {
                     "tags": {
                      "AVATAR_LAYER": {
                       "config_type": 0
                      },
                      "GENERAL_CFG": {
                       "config_type": 1,
                       "general_config": {
                        "web_css_style": {
                         "borderRadius": "50%"
                        }
                       }
                      }
                     },
                     "is_critical": true,
                     "allow_over_paint": false
                    },
                    "resource": {
                     "res_type": 3,
                     "res_image": {
                      "image_src": {
                       "src_type": 1,
                       "placeholder": 6,
                       "remote": {
                        "url": "https://i0.hdslb.com/bfs/face/df90749d015bc888340c5b1cf0ce00357b75437b.jpg",
                        "bfs_style": "widget-layer-avatar"
                       }
                      }
                     }
                    }
                   }
                  ],
                  "is_critical_group": true
                 },
                 "mid": "1276196739"
                },
                "face": "https://i0.hdslb.com/bfs/face/df90749d015bc888340c5b1cf0ce00357b75437b.jpg",
                "face_nft": false,
                "name": "bluepotato_EP",
                "label": "",
                "mid": 1276196739,
                "jump_url": "//space.bilibili.com/1276196739/dynamic",
                "following": 2,
                "pub_ts": "1788576705",
                "pub_time": "",
                "pub_action": "投稿了视频",
                "pub_location_text": "",
                "pendant": {
                 "pid": 0,
                 "name": "",
                 "image": "",
                 "expire": "0",
                 "image_enhance": "",
                 "image_enhance_frame": "",
                 "n_pid": "0"
                },
                "vip": {
                 "type": 1,
                 "status": 0,
                 "due_date": "1743523200000",
                 "vip_pay_type": 0,
                 "theme_type": 0,
                 "label": {
                  "path": "",
                  "text": "",
                  "label_theme": "",
                  "text_color": "",
                  "bg_style": 0,
                  "bg_color": "",
                  "border_color": "",
                  "use_img_label": true,
                  "img_label_uri_hans": "",
                  "img_label_uri_hant": "",
                  "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/d7b702ef65a976b20ed854cbd04cb9e27341bb79.png",
                  "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/KJunwh19T5.png"
                 },
                 "avatar_subscript": 0,
                 "nickname_color": "",
                 "role": "0",
                 "avatar_subscript_url": "",
                 "tv_vip_status": 0,
                 "tv_vip_pay_type": 0,
                 "tv_due_date": "0",
                 "avatar_icon": {
                  "icon_type": 0,
                  "icon_resource": {
                   "type": 0,
                   "url": ""
                  }
                 }
                },
                "official_verify": {
                 "type": -1,
                 "desc": ""
                },
                "decoration_card": {
                 "id": "71752",
                 "item_id": "71752",
                 "name": "少女乐队的呐喊勋章",
                 "card_url": "https://i0.hdslb.com/bfs/garb/open/5949cb2d997c55674befadb4499f911fb8481231.png",
                 "big_card_url": "https://i0.hdslb.com/bfs/garb/open/5949cb2d997c55674befadb4499f911fb8481231.png",
                 "card_type": "2",
                 "expire_time": "0",
                 "card_type_name": "免费",
                 "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=104978&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A1276196739%7D",
                 "fan": {
                  "is_fan": "1",
                  "number": "40165",
                  "color": "#BFC8D2",
                  "name": "XXXX",
                  "num_desc": "040165",
                  "num_prefix": "",
                  "color_format": {
                   "start_point": "0,0",
                   "end_point": "100,100",
                   "colors": [
                    "#B8C7D0FF",
                    "#A2A7B0FF"
                   ],
                   "gradients": [
                    "0",
                    "100"
                   ]
                  }
                 },
                 "image_enhance": "https://i0.hdslb.com/bfs/garb/open/5949cb2d997c55674befadb4499f911fb8481231.png"
                },
                "is_top": false,
                "views_text": "",
                "decorate_card": {
                 "id": "71752",
                 "item_id": "71752",
                 "name": "少女乐队的呐喊勋章",
                 "card_url": "https://i0.hdslb.com/bfs/garb/open/5949cb2d997c55674befadb4499f911fb8481231.png",
                 "big_card_url": "https://i0.hdslb.com/bfs/garb/open/5949cb2d997c55674befadb4499f911fb8481231.png",
                 "card_type": "2",
                 "expire_time": "0",
                 "card_type_name": "免费",
                 "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=104978&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A1276196739%7D",
                 "fan": {
                  "is_fan": "1",
                  "number": "40165",
                  "color": "#BFC8D2",
                  "name": "XXXX",
                  "num_desc": "040165",
                  "num_prefix": "",
                  "color_format": {
                   "start_point": "0,0",
                   "end_point": "100,100",
                   "colors": [
                    "#B8C7D0FF",
                    "#A2A7B0FF"
                   ],
                   "gradients": [
                    "0",
                    "100"
                   ]
                  }
                 },
                 "image_enhance": "https://i0.hdslb.com/bfs/garb/open/5949cb2d997c55674befadb4499f911fb8481231.png"
                }
               },
               "module_dynamic": {
                "major": {
                 "type": "MAJOR_TYPE_ARCHIVE",
                 "archive": {
                  "type": 1,
                  "bvid": "BV1ySt16CErJ",
                  "aid": "117216150293412",
                  "cover": "http://i0.hdslb.com/bfs/archive/95f6824e6c2f622f88fc37da3099439488f7d9c3.jpg",
                  "jump_url": "//www.bilibili.com/video/BV1ySt16CErJ",
                  "stat": {
                   "danmaku": "4",
                   "play": "3524",
                   "vt": ""
                  },
                  "duration_text": "01:33",
                  "title": "使用GPT-6 Astra手绘初音！",
                  "desc": "转载自https://x.com/qibiz_me/status/2096000743786627103",
                  "badge": {
                   "icon_url": "",
                   "text": "投稿视频",
                   "bg_color": "#FB7299",
                   "color": "#FFFFFF"
                  },
                  "enable_vt": 0,
                  "disable_preview": 0,
                  "premiere_online": "",
                  "stat_hidden": 0
                 }
                }
               }
              },
              "type": "DYNAMIC_TYPE_AV",
              "visible": true
             },
             "type": "DYNAMIC_TYPE_FORWARD",
             "visible": true
            }
            """;

    private static final String LIVE_RCMD_JSON = """
            {
             "basic": {
              "rid_str": "732762115538714041",
              "comment_type": 17,
              "comment_id_str": "1244005000624996354",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "",
              "aigc": false
             },
             "id_str": "1244005000624996354",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.65,
                 "height": 1.65
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.825,
                     "axis_y": 0.825
                    },
                    "size_spec": {
                     "width": 0.94,
                     "height": 0.94
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 4,
                    "res_animation": {
                     "webp_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i0.hdslb.com/bfs/face/39575105db45fee5d766340fa23036255aa7c392.webp",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.825,
                     "axis_y": 0.825
                    },
                    "size_spec": {
                     "width": 1.65,
                     "height": 1.65
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i0.hdslb.com/bfs/garb/open/2ac84e57087324d69a317b67697896b339492414.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 1,
                     "axis_x": 1.0116666666666665,
                     "axis_y": 1.028333333333333
                    },
                    "size_spec": {
                     "width": 0.375,
                     "height": 0.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "background-color": "rgb(255,255,255)",
                        "border": "2px solid rgba(255,255,255,1)",
                        "borderRadius": "50%",
                        "boxSizing": "border-box"
                       }
                      }
                     },
                     "ICON_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 2,
                      "placeholder": 0,
                      "local": 3
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 1,
                     "axis_x": 0.6783333333333333,
                     "axis_y": 1.028333333333333
                    },
                    "size_spec": {
                     "width": 0.375,
                     "height": 0.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "background-color": "rgb(255,255,255)",
                        "border": "2px solid rgba(255,255,255,1)",
                        "borderRadius": "50%",
                        "boxSizing": "border-box"
                       }
                      }
                     },
                     "ICON_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 4,
                    "res_animation": {
                     "webp_src": {
                      "src_type": 1,
                      "placeholder": 5,
                      "remote": {
                       "url": "https://i0.hdslb.com/bfs/activity-plat/static/20220506/334553dd7c506a92b88eaf4d59ac8b4d/j8AeXAkEul.gif",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "3461566982785482"
               },
               "face": "https://i0.hdslb.com/bfs/face/39575105db45fee5d766340fa23036255aa7c392.webp",
               "face_nft": false,
               "nft_info": {
                "region_type": 1,
                "region_icon": "https://i0.hdslb.com/bfs/activity-plat/static/20220506/334553dd7c506a92b88eaf4d59ac8b4d/j8AeXAkEul.gif",
                "region_show_status": 1
               },
               "name": "芒芒_Mou",
               "label": "",
               "mid": 3461566982785482,
               "jump_url": "//space.bilibili.com/3461566982785482/dynamic",
               "following": 1,
               "pub_ts": "1788480885",
               "pub_time": "",
               "pub_action": "直播了",
               "pub_location_text": "",
               "pendant": {
                "pid": -1149390879,
                "name": "芒芒_Mou收藏集",
                "image": "https://i0.hdslb.com/bfs/garb/open/2ac84e57087324d69a317b67697896b339492414.png",
                "expire": "0",
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/2ac84e57087324d69a317b67697896b339492414.png",
                "image_enhance_frame": "",
                "n_pid": "1738312364001"
               },
               "vip": {
                "type": 2,
                "status": 1,
                "due_date": "1799337600000",
                "vip_pay_type": 1,
                "theme_type": 0,
                "label": {
                 "path": "http://i0.hdslb.com/bfs/vip/label_annual.png",
                 "text": "年度大会员",
                 "label_theme": "annual_vip",
                 "text_color": "#FFFFFF",
                 "bg_style": 1,
                 "bg_color": "#FB7299",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/8d4f8bfc713826a5412a0a27eaaac4d6b9ede1d9.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/VEW8fCC0hg.png"
                },
                "avatar_subscript": 1,
                "nickname_color": "#FB7299",
                "role": "3",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "1704211200",
                "avatar_icon": {
                 "icon_type": 1,
                 "icon_resource": {
                  "type": 0,
                  "url": ""
                 }
                }
               },
               "official_verify": {
                "type": 0,
                "desc": ""
               },
               "decoration_card": {
                "id": "73738",
                "item_id": "73738",
                "name": "四茶睡大觉",
                "card_url": "https://i0.hdslb.com/bfs/garb/open/64d03d31039de1cd91a49c5ce81a0dd040aa3804.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/open/64d03d31039de1cd91a49c5ce81a0dd040aa3804.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=108761&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A3461566982785482%7D",
                "fan": {
                 "is_fan": "1",
                 "number": "419",
                 "color": "#BFC8D2",
                 "name": "XXXX",
                 "num_desc": "000419",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "100,100",
                  "colors": [
                   "#B8C7D0FF",
                   "#A2A7B0FF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/64d03d31039de1cd91a49c5ce81a0dd040aa3804.png"
               },
               "is_top": false,
               "views_text": "",
               "decorate_card": {
                "id": "73738",
                "item_id": "73738",
                "name": "四茶睡大觉",
                "card_url": "https://i0.hdslb.com/bfs/garb/open/64d03d31039de1cd91a49c5ce81a0dd040aa3804.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/open/64d03d31039de1cd91a49c5ce81a0dd040aa3804.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=108761&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A3461566982785482%7D",
                "fan": {
                 "is_fan": "1",
                 "number": "419",
                 "color": "#BFC8D2",
                 "name": "XXXX",
                 "num_desc": "000419",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "100,100",
                  "colors": [
                   "#B8C7D0FF",
                   "#A2A7B0FF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/64d03d31039de1cd91a49c5ce81a0dd040aa3804.png"
               }
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "major": {
                "type": "MAJOR_TYPE_LIVE_RCMD",
                "live_rcmd": {
                 "reserve_type": 0,
                 "content": "{\\"type\\":1,\\"live_play_info\\":{\\"room_id\\":25984441,\\"uid\\":3461566982785482,\\"live_status\\":1,\\"room_type\\":0,\\"play_type\\":0,\\"title\\":\\"【歌杂杂】堡来\\",\\"cover\\":\\"https://i0.hdslb.com/bfs/live/new_room_cover/01949cb141d564bb8bc006c716478d3d66f2eb6c.jpg\\",\\"online\\":16488,\\"area_id\\":744,\\"area_name\\":\\"虚拟Singer\\",\\"parent_area_id\\":9,\\"parent_area_name\\":\\"虚拟主播\\",\\"live_screen_type\\":0,\\"live_start_time\\":1788480282,\\"link\\":\\"//live.bilibili.com/25984441?live_from=85002\\",\\"live_id\\":732762115538714041,\\"pendants\\":{\\"list\\":null},\\"watched_show\\":{\\"switch\\":true,\\"num\\":337,\\"text_small\\":\\"337\\",\\"text_large\\":\\"337人看过\\",\\"icon\\":\\"https://i0.hdslb.com/bfs/live/a725a9e61242ef44d764ac911691a7ce07f36c1d.png\\",\\"icon_location\\":\\"\\",\\"icon_web\\":\\"https://i0.hdslb.com/bfs/live/8d9d0f33ef8bf6f308742752d13dd0df731df19c.png\\"},\\"room_paid_type\\":0},\\"live_record_info\\":null}"
                }
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": true
               },
               "like": {
                "status": false,
                "count": 3,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "type": "DYNAMIC_TYPE_LIVE_RCMD",
             "visible": true
            }
            """;

    private static final String WORD_JSON = """
            {
             "basic": {
              "rid_str": "1244067913352085554",
              "comment_type": 17,
              "comment_id_str": "1244067913352085554",
              "like_icon": {
               "id": "0",
               "start_url": "",
               "action_url": "",
               "end_url": ""
              },
              "in_audit": false,
              "is_only_fans": false,
              "editable": false,
              "open_app_extra": "",
              "jump_url": "//www.bilibili.com/opus/1244067913352085554",
              "aigc": false
             },
             "id_str": "1244067913352085554",
             "modules": {
              "module_author": {
               "type": "AUTHOR_TYPE_NORMAL",
               "avatar": {
                "container_size": {
                 "width": 1.375,
                 "height": 1.375
                },
                "layers": [],
                "fallback_layers": {
                 "group_id": "",
                 "layers": [
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 0.787,
                     "height": 0.787
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "AVATAR_LAYER": {
                      "config_type": 0
                     },
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "borderRadius": "50%"
                       }
                      }
                     }
                    },
                    "is_critical": true,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 6,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/face/6e063326fd027cf058c5ac9f7f387dd92568a77d.jpg",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 2,
                     "axis_x": 0.6875,
                     "axis_y": 0.6875
                    },
                    "size_spec": {
                     "width": 1.375,
                     "height": 1.375
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "PENDENT_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 1,
                      "placeholder": 0,
                      "remote": {
                       "url": "https://i1.hdslb.com/bfs/garb/open/c8dc71a2fcecbeb0ed58ddf8b3c0ce678e0a44e0.png",
                       "bfs_style": "widget-layer-avatar"
                      }
                     }
                    }
                   }
                  },
                  {
                   "layer_id": "",
                   "visible": true,
                   "general_spec": {
                    "pos_spec": {
                     "coordinate_pos": 1,
                     "axis_x": 0.7560000000000001,
                     "axis_y": 0.7726666666666667
                    },
                    "size_spec": {
                     "width": 0.41666666666666663,
                     "height": 0.41666666666666663
                    },
                    "render_spec": {
                     "opacity": 1
                    }
                   },
                   "layer_config": {
                    "tags": {
                     "GENERAL_CFG": {
                      "config_type": 1,
                      "general_config": {
                       "web_css_style": {
                        "background-color": "rgb(255,255,255)",
                        "border": "2px solid rgba(255,255,255,1)",
                        "borderRadius": "50%",
                        "boxSizing": "border-box"
                       }
                      }
                     },
                     "ICON_LAYER": {
                      "config_type": 0
                     }
                    },
                    "is_critical": false,
                    "allow_over_paint": false
                   },
                   "resource": {
                    "res_type": 3,
                    "res_image": {
                     "image_src": {
                      "src_type": 2,
                      "placeholder": 0,
                      "local": 3
                     }
                    }
                   }
                  }
                 ],
                 "is_critical_group": true
                },
                "mid": "632171719"
               },
               "face": "https://i1.hdslb.com/bfs/face/6e063326fd027cf058c5ac9f7f387dd92568a77d.jpg",
               "face_nft": false,
               "name": "紫贝儿official",
               "label": "",
               "mid": 632171719,
               "jump_url": "//space.bilibili.com/632171719/dynamic",
               "following": 1,
               "pub_ts": "1788495533",
               "pub_time": "刚刚",
               "pub_action": "",
               "pub_location_text": "",
               "pendant": {
                "pid": -1162352399,
                "name": "美味中华",
                "image": "https://i1.hdslb.com/bfs/garb/open/c8dc71a2fcecbeb0ed58ddf8b3c0ce678e0a44e0.png",
                "expire": "0",
                "image_enhance": "https://i1.hdslb.com/bfs/garb/open/c8dc71a2fcecbeb0ed58ddf8b3c0ce678e0a44e0.png",
                "image_enhance_frame": "",
                "n_pid": "1716824566001"
               },
               "vip": {
                "type": 2,
                "status": 1,
                "due_date": "1816963200000",
                "vip_pay_type": 1,
                "theme_type": 0,
                "label": {
                 "path": "http://i0.hdslb.com/bfs/vip/label_annual.png",
                 "text": "年度大会员",
                 "label_theme": "annual_vip",
                 "text_color": "#FFFFFF",
                 "bg_style": 1,
                 "bg_color": "#FB7299",
                 "border_color": "",
                 "use_img_label": true,
                 "img_label_uri_hans": "",
                 "img_label_uri_hant": "",
                 "img_label_uri_hans_static": "https://i0.hdslb.com/bfs/vip/8d4f8bfc713826a5412a0a27eaaac4d6b9ede1d9.png",
                 "img_label_uri_hant_static": "https://i0.hdslb.com/bfs/activity-plat/static/20220614/e369244d0b14644f5e1a06431e22a4d5/VEW8fCC0hg.png"
                },
                "avatar_subscript": 1,
                "nickname_color": "#FB7299",
                "role": "3",
                "avatar_subscript_url": "",
                "tv_vip_status": 0,
                "tv_vip_pay_type": 0,
                "tv_due_date": "1757001600",
                "avatar_icon": {
                 "icon_type": 1,
                 "icon_resource": {
                  "type": 0,
                  "url": ""
                 }
                }
               },
               "official_verify": {
                "type": 0,
                "desc": ""
               },
               "decoration_card": {
                "id": "72402",
                "item_id": "72402",
                "name": "紫贝儿·年味",
                "card_url": "https://i0.hdslb.com/bfs/garb/open/1ebb0ce60fb904211dbcec0702ecabf55192e03e.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/open/1ebb0ce60fb904211dbcec0702ecabf55192e03e.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=105579&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A632171719%7D",
                "fan": {
                 "is_fan": "1",
                 "number": "1",
                 "color": "#BFC8D2",
                 "name": "XXXX",
                 "num_desc": "000001",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "100,100",
                  "colors": [
                   "#B8C7D0FF",
                   "#A2A7B0FF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/1ebb0ce60fb904211dbcec0702ecabf55192e03e.png"
               },
               "is_top": false,
               "views_text": "",
               "decorate_card": {
                "id": "72402",
                "item_id": "72402",
                "name": "紫贝儿·年味",
                "card_url": "https://i0.hdslb.com/bfs/garb/open/1ebb0ce60fb904211dbcec0702ecabf55192e03e.png",
                "big_card_url": "https://i0.hdslb.com/bfs/garb/open/1ebb0ce60fb904211dbcec0702ecabf55192e03e.png",
                "card_type": "2",
                "expire_time": "0",
                "card_type_name": "免费",
                "jump_url": "https://www.bilibili.com/h5/mall/digital-card/home?act_id=105579&lottery_id=0&hybrid_set_header=2&anchor_task=1&navhide=1&f_source=garb&from=post&window_params=%7B%22type%22%3A2%2C%22mid%22%3A632171719%7D",
                "fan": {
                 "is_fan": "1",
                 "number": "1",
                 "color": "#BFC8D2",
                 "name": "XXXX",
                 "num_desc": "000001",
                 "num_prefix": "",
                 "color_format": {
                  "start_point": "0,0",
                  "end_point": "100,100",
                  "colors": [
                   "#B8C7D0FF",
                   "#A2A7B0FF"
                  ],
                  "gradients": [
                   "0",
                   "100"
                  ]
                 }
                },
                "image_enhance": "https://i0.hdslb.com/bfs/garb/open/1ebb0ce60fb904211dbcec0702ecabf55192e03e.png"
               }
              },
              "module_more": {
               "rcmd_text": "",
               "three_point_items": [
                {
                 "label": "取消关注",
                 "type": "THREE_POINT_FOLLOWING",
                 "params": {},
                 "jump_url": ""
                },
                {
                 "label": "举报",
                 "type": "THREE_POINT_REPORT",
                 "params": {},
                 "jump_url": ""
                }
               ]
              },
              "module_dynamic": {
               "major": {
                "type": "MAJOR_TYPE_OPUS",
                "opus": {
                 "jump_url": "//www.bilibili.com/opus/1244067913352085554",
                 "title": "",
                 "summary": {
                  "text": "点击预约按钮，不错过直播",
                  "rich_text_nodes": [
                   {
                    "text": "点击预约按钮，不错过直播",
                    "orig_text": "点击预约按钮，不错过直播",
                    "type": "RICH_TEXT_NODE_TYPE_TEXT",
                    "jump_url": "",
                    "icon_url": "",
                    "icon_name": "",
                    "rid": "",
                    "pics": []
                   }
                  ],
                  "paragraphs": [],
                  "has_more": false
                 },
                 "style": 0,
                 "pics": [],
                 "fold_action": [
                  "展开",
                  "收起"
                 ]
                }
               },
               "additional": {
                "type": "ADDITIONAL_TYPE_RESERVE",
                "reserve": {
                 "title": "直播预约：紫贝阙音乐电台：慢慢唱给你",
                 "desc1": {
                  "text": "明天 12:00 直播",
                  "style": 0,
                  "jump_url": "",
                  "icon_url": "",
                  "visible": false
                 },
                 "desc2": {
                  "text": "0人预约",
                  "style": 0,
                  "jump_url": "",
                  "icon_url": "",
                  "visible": false
                 },
                 "badge_text": "",
                 "jump_url": "",
                 "button": {
                  "type": 2,
                  "jump_url": "",
                  "check": {
                   "icon_url": "",
                   "text": "已预约",
                   "bg_style": 0,
                   "toast": "",
                   "disable": 0
                  },
                  "uncheck": {
                   "icon_url": "https://i0.hdslb.com/bfs/album/1d6af68e116985828780dd843ef435ccd6307e63.png",
                   "text": "预约",
                   "bg_style": 0,
                   "toast": "",
                   "disable": 0
                  },
                  "status": 1,
                  "click_type": 0
                 },
                 "rid": 5754413,
                 "reserve_total": 0,
                 "state": 0,
                 "stype": 2,
                 "up_mid": "632171719"
                }
               }
              },
              "module_stat": {
               "forward": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "comment": {
                "status": false,
                "count": 0,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               },
               "like": {
                "status": false,
                "count": 1,
                "forbidden": false,
                "disabled": false,
                "silent": false,
                "hidden": false
               }
              }
             },
             "type": "DYNAMIC_TYPE_WORD",
             "visible": true
            }
            """;

    /**
     * 动态测试数据
     *
     * @param type 动态类型标识
     * @param json 动态原始 JSON
     */
    private record DynamicTestData(String type, String json) {
    }

    private static final List<DynamicTestData> DYNAMIC_DATA = List.of(
            new DynamicTestData("ARTICLE", ARTICLE_JSON),
            new DynamicTestData("AV", AV_JSON),
            new DynamicTestData("COMMON_SQUARE", COMMON_SQUARE_JSON),
            new DynamicTestData("DRAW", DRAW_JSON),
            new DynamicTestData("FORWARD", FORWARD_JSON),
            new DynamicTestData("LIVE_RCMD", LIVE_RCMD_JSON),
            new DynamicTestData("WORD", WORD_JSON)
    );

    private static StarBotBilibiliProperties properties;

    private static BilibiliApiUtil bilibili;

    private static BilibiliDynamicPainterFactory factory;

    /**
     * 初始化测试绘图环境
     * <p>
     * 构建真实 {@link FontUtil}（指定系统字体，避免绘制文字时无可用字体异常）、真实 {@link StarBotCommonPainterFactory}，
     * 并 mock {@link BilibiliApiUtil} 返回占位图，使动态绘制链路与生产环境一致，仅图片来源与配置数据被替代
     */
    @BeforeAll
    public static void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        coreProperties.getPaint().setFonts(List.of("微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial"));
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        StarBotCommonPainterFactory commonFactory = new StarBotCommonPainterFactory(mock(BuildProperties.class), coreProperties, fontUtil);

        properties = new StarBotBilibiliProperties();

        bilibili = mock(BilibiliApiUtil.class);
        when(bilibili.getBilibiliImage(anyString())).thenAnswer(invocation -> Optional.of(createPlaceholderImage()));
        when(bilibili.asyncGetBilibiliImages(anyList())).thenAnswer(invocation -> {
            List<String> urls = invocation.getArgument(0);
            return CompletableFuture.completedFuture(urls.stream().map(url -> Optional.of(createPlaceholderImage())).toList());
        });

        factory = new BilibiliDynamicPainterFactory(properties, fontUtil, bilibili, commonFactory);
    }

    /**
     * 绘制内嵌的各类型动态，并校验绘制成功
     */
    @Test
    public void testPaintDynamic() throws IOException {
        int painted = 0;
        for (DynamicTestData data : DYNAMIC_DATA) {
            Dynamic dynamic = JSON.parseObject(data.json(), Dynamic.class);
            BilibiliDynamicPainter painter = factory.create(dynamic);

            Optional<String> result;
            if (SAVE_IMAGE) {
                Path outputPath = Paths.get(OUTPUT_DIR);
                Files.createDirectories(outputPath);
                String outputFile = outputPath.resolve(data.type() + "-" + dynamic.getId() + ".png").toString();
                result = painter.paint(outputFile);
                System.out.println("已绘制: " + data.type() + "/" + dynamic.getId() + " -> " + outputFile);
            } else {
                result = painter.paint();
            }

            assertTrue(result.isPresent(), "绘制动态 " + data.type() + "(" + dynamic.getId() + ") 失败");
            painted++;
        }

        assertTrue(painted > 0, "应至少绘制一条动态");
    }

    /**
     * 创建占位图片
     *
     * @return 灰色占位图片
     */
    private static BufferedImage createPlaceholderImage() {
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(210, 210, 210));
        graphics.fillRect(0, 0, 600, 600);
        graphics.dispose();
        return image;
    }
}
