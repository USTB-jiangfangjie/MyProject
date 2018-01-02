package com.sunlands.community.api.post.REST;

import com.alibaba.fastjson.JSON;
import com.shangde.common.dto.BaseSearchResultDTO;
import com.shangde.common.exception.BaseException;
import com.shangde.common.exception.ExceptionCategory;
import com.shangde.common.mail.bean.Mail;
import com.sunlands.community.album.service.AlbumAdminService;
import com.sunlands.community.album.util.AlbumCache;
import com.sunlands.community.common.JsonDateValueProcessor;
import com.sunlands.community.common.cache.redis.JedisClient;
import com.sunlands.community.common.dict.util.DictConstants;
import com.sunlands.community.common.dict.util.RedisKeyPrefix;
import com.sunlands.community.common.entity.ResTopic;
import com.sunlands.community.common.entity.TTopic;
import com.sunlands.community.common.perf.Statistics;
import com.sunlands.community.common.service.GradeService;
import com.sunlands.community.common.service.PersonalService;
import com.sunlands.community.common.service.PostTopicRelationService;
import com.sunlands.community.common.service.TopicService;
import com.sunlands.community.common.utils.*;
import com.sunlands.community.commonality.service.MiPushService;
import com.sunlands.community.permission.service.UserPermissionService;
import com.sunlands.community.post.dmo.*;
import com.sunlands.community.post.dto.*;
import com.sunlands.community.score.dto.ReqScoreRecordDTO;
import com.sunlands.community.score.service.ScoreSystemService;
import com.sunlands.community.service.*;
import com.sunlands.community.thrift.service.CheckIsVipClient;
import com.sunlands.community.thrift.service.Student;
import com.sunlands.minor.service.ManagerPostService;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JsonConfig;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.ws.rs.Path;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.*;

@Path("")
public class ApiPostServiceImpl implements ApiPostService {

    private static Logger logger = LoggerFactory.getLogger(ApiPostServiceImpl.class);

    @Autowired
    private PostMasterService postMasterService;

    @Autowired
    private PostSlaveService postSlaveService;

    @Autowired
    private PostReplyService postReplyService;

    @Autowired
    private AlbumCache albumCache;

    @Autowired
    private UserPermissionService permissionService;

    @Autowired
    private PostOperationLogService postOperationLogService;

    @Autowired
    private CheckIsVipClient checkIsVipClient;

    @Autowired
    private AlbumAdminService albumAdminService;

    @Autowired
    private ScoreSystemService scoreSystemService;

    @Autowired
    private PostSlaveOperationLogService postSlaveOperationLogService;

    @Autowired
    private CheckPermissionsUtils checkPermissionsUtils;

    @Value("${personalCenterURL}")
    private String personalCenterURL;

    @Autowired
    private ManagerPostService managerPostService;

    @Autowired
    private PostSensitiveService postSensitiveService;

    @Autowired
    private MiPushService miPushService;

    @Autowired
    private PostReplyOperationLogService postReplyOperationLogService;

    @Autowired
    private TopicService topicService;

    @Autowired
    PostTopicRelationService postTopicRelationService;

    @Autowired
    private GradeService gradeService;

    @Autowired
    private MailSendUtils mailSendUtils;

    @Autowired
    private PersonalService personalService;

    @Autowired
    private JedisClient redisClient;
    

    @Override
    public String sendPostMasterByUserId(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("sendPostByUserId(String jsonStr={})	-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);
            if (postMasterDTO.getContent() != null && "null".equals(postMasterDTO.getContent())) {

                postMasterDTO.setContent(StringUtil.dealWithNull(postMasterDTO.getContent()));
            }
            if (postMasterDTO.getPostSubject() != null && "null".equals(postMasterDTO.getPostSubject())) {

                postMasterDTO.setPostSubject(StringUtil.dealWithNull(postMasterDTO.getPostSubject()));
            }


            if (checkPermissionsUtils.checkBannedByUserId(postMasterDTO.getUserId())) {

                //禁言
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "您已被管理员禁言");

                return rstJson.toString();

            }

            int rst = postMasterService.savePostMaster(postMasterDTO);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "发表主贴失败");
            } else {
                Integer albumParentId = postMasterDTO.getAlbumParentId();
                Integer albumChildId = postMasterDTO.getAlbumChildId();

                if (albumParentId == null) {
                    albumParentId = 0;
                }

                if (albumChildId == null) {
                    albumChildId = 0;
                }

                albumCache.updateAlbumLastPostTime(albumParentId, 0, new Date());
                albumCache.updateAlbumMasterTotalNum(albumParentId, 0, 1);
                albumCache.updateAlbumNewPostNum(albumParentId, 0, 1);

                if (!albumChildId.equals(0)) {
                    albumCache.updateAlbumLastPostTime(0, albumChildId, new Date());
                    albumCache.updateAlbumMasterTotalNum(0, albumChildId, 1);
                    albumCache.updateAlbumNewPostNum(0, albumChildId, 1);
                }
                try {
                    ReqScoreRecordDTO reqScoreRecordDTO = new ReqScoreRecordDTO();
                    reqScoreRecordDTO.setUserId(postMasterDTO.getUserId());
                    reqScoreRecordDTO.setRelId(rst);
                    reqScoreRecordDTO.setN((double) 0);
                    reqScoreRecordDTO.setRuleCode(DictConstants.SHEQU_SEND_MAIN_POST);
                    reqScoreRecordDTO.setRuleType(DictConstants.RULETYPE);
                    reqScoreRecordDTO
                            .setChannelSource(postMasterService.getChannelScource(postMasterDTO.getOsVersion()));
                    reqScoreRecordDTO.setEncryptStr(MD5.getAddScoreRecordValue(reqScoreRecordDTO.getUserId(),
                            reqScoreRecordDTO.getRuleCode(), reqScoreRecordDTO.getChannelSource()));
                    String data = scoreSystemService.packagingAddScoreRecordData(reqScoreRecordDTO);
                    String result = scoreSystemService.getResponse(personalCenterURL + DictConstants.ADD_SCORE_PATH,
                            data);
                    String rsdesp = scoreSystemService.getAddScoreRecordRemark(result);
                    if (rsdesp != null) {
                        rstJson.put("rsdesp", rsdesp);
                    }
                } catch (UnsupportedEncodingException e) {
                    logger.debug("行为: 发主贴, 请求个人中心(增加积分记录接口)失败!");
                }
                //关联话题
                try {
                    if (postMasterDTO.getSensitiveWord() == null || postMasterDTO.getSensitiveWord().isEmpty()) {//帖子中不包含敏感词
                        String content = postMasterDTO.getContent();
                        //从帖子内容中获取话题名称列表
                        List<String> topicNameList = StringUtil.getTopicNameFromContent(content);
                        for (String topicName : topicNameList) {
                            //##之间的字符全部是特殊字符的话(不包含中英文)，则不生成话题
                            if (StringUtil.isAllSpeCharacters(topicName)) {
                                continue;
                            }
                            ResTopic topic = topicService.selectTopicByTitle(topicName);
                            //现有话题
                            if (topic != null) {
                                if (topic.getIsShow()) {
                                    postTopicRelationService.adminSetTopicOnPost(String.valueOf(postMasterDTO.getPostMasterId()), 1, topic.getTopicId());
                                } else {//隐藏的官方话题不做任何处理，默认只有官方话题才有隐藏状态

                                }
                                if (topic.getTopicType() == 1) {//官方话题，设置最新发帖时间
                                    redisClient.set(RedisKeyPrefix.POST_TIME_TOPIC + topic.getTopicId(), DateUtils.getDateTime());
                                }
                            } else {//用户自定义话题
                                TTopic tTopic = new TTopic();
                                tTopic.setTopicTitle(topicName);
                                tTopic.setIsShow(true);
                                tTopic.setDeleteFlag(false);
                                tTopic.setTopicBrief("");
                                tTopic.setMediaLinks("");
                                tTopic.setTopicType((byte) 2);
                                tTopic.setCreater(postMasterDTO.getEmail());
                                tTopic.setUpdater(postMasterDTO.getEmail());
                                tTopic.setTopicWeight((short) 101);//官方的权重0-100，自定的100意外，这里默认给101
                                Integer topicId = topicService.adminAddTopic(tTopic);
                                postTopicRelationService.adminSetTopicOnPost(String.valueOf(postMasterDTO.getPostMasterId()), 1, topicId);
                            }
                        }
                    }


                } catch (Exception e) {
                    logger.debug("话题关联失败");
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "发表主贴失败!" + e.getMessage());
            if (e.getMessage().contains("用户没有发帖权限")) {
                rstJson.put("rsdesp", "发帖失败!您没有发帖权限!");
            }
            return rstJson.toString();
        }

        logger.debug("sendPostByUserId(String jsonStr={})	-- end", jsonStr);

        return rstJson.toString();
    }

    @Override
    public String deletePostMasterByUserId(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("deletePostByUserId(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMaster = null;

        try {
            // 参数校验
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMaster = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMaster.getPostMasterId() == null || postMaster.getUserId() == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "主贴id 或者 用户名 不能为空");
                return rstJson.toString();
            }

            // 设置删除标识
            postMaster.setDeleteFlag(NumberUtils.BYTE_ONE);

            // 更新主贴信息
            int rst = postMasterService.updateState(postMaster);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "删除主贴失败");
                return rstJson.toString();
            } else {
                ResPostMasterDTO resPostMasterDTO = postMasterService
                        .retrieveByPostMasterId(postMaster.getPostMasterId());

                if (resPostMasterDTO == null) {
                    return rstJson.toString();
                }

                Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                if (albumParentId == null) {
                    albumParentId = 0;
                }

                if (albumChildId == null) {
                    albumChildId = 0;
                }

                albumCache.updateAlbumMasterTotalNum(albumParentId, 0, -1);
                albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                if (!albumChildId.equals(0)) {
                    albumCache.updateAlbumMasterTotalNum(0, albumChildId, -1);
                    albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除主贴失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("deletePostByUserId(String jsonStr={})	--end", jsonStr);

        return rstJson.toString();
    }

    @Override
    public String deletePostMasterByManager(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("deletePostMasterByManager(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMaster = null;

        try {
            // 参数校验
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMaster = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            int rst = 0;
            //
            if (postMaster.getOperateFlag() != null && postMaster.getOperateFlag() == 1) {
                // 批量操作
                List<Integer> postMasterIds = postMaster.getPostMasterIds();
                if (postMasterIds == null || postMaster.getEmail() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "主贴id集合 或者 操做人邮箱  不能为空");
                    return rstJson.toString();
                }
                // 设置删除标识
                postMaster.setDeleteFlag(NumberUtils.BYTE_ONE);

                if (!postMasterIds.isEmpty()) {
                    for (Integer postMasterId : postMasterIds) {
                        postMaster.setPostMasterId(postMasterId);
                        // 更新主贴信息
                        rst = postMasterService.modifyMasterDeleteFlag(postMaster);
                        if (rst == 0) {
                            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                            rstJson.put("rsdesp", "删除主贴失败");
                            return rstJson.toString();
                        } else {
                            ResPostMasterDTO resPostMasterDTO = postMasterService
                                    .retrieveByPostMasterId(postMaster.getPostMasterId());

                            postMasterService.reduceReplyCount(postMaster.getPostMasterId());
                            if (resPostMasterDTO == null) {
                                return rstJson.toString();
                            }

                            Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                            Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                            if (albumParentId == null) {
                                albumParentId = 0;
                            }

                            if (albumChildId == null) {
                                albumChildId = 0;
                            }

                            albumCache.updateAlbumMasterTotalNum(albumParentId, 0, -1);
                            albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                            if (!albumChildId.equals(0)) {
                                albumCache.updateAlbumMasterTotalNum(0, albumChildId, -1);
                                albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                            }

                        }

                    }
                }
            } else {

                if (postMaster.getEmail() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "主贴id 或者 操做人邮箱  不能为空");
                    return rstJson.toString();
                }

                // 设置删除标识
                postMaster.setDeleteFlag(NumberUtils.BYTE_ONE);

                // 更新主贴信息
                rst = postMasterService.modifyMasterDeleteFlag(postMaster);
                if (rst == 0) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "删除主贴失败");
                    return rstJson.toString();
                } else {
                    ResPostMasterDTO resPostMasterDTO = postMasterService
                            .retrieveByPostMasterId(postMaster.getPostMasterId());
                    postMasterService.reduceReplyCount(postMaster.getPostMasterId());
                    if (resPostMasterDTO == null) {
                        return rstJson.toString();
                    }

                    Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                    Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                    if (albumParentId == null) {
                        albumParentId = 0;
                    }

                    if (albumChildId == null) {
                        albumChildId = 0;
                    }

                    albumCache.updateAlbumMasterTotalNum(albumParentId, 0, -1);
                    albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                    if (!albumChildId.equals(0)) {
                        albumCache.updateAlbumMasterTotalNum(0, albumChildId, -1);
                        albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                    }

                }
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除主贴失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("deletePostMasterByManager(String jsonStr={})	--end", jsonStr);

        return rstJson.toString();
    }

    @Override
    public String recoverPostMasterByManager(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("deletePostMasterByManager(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMaster = null;

        try {
            // 参数校验
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMaster = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            ResPostMasterDTO resPostMasterDTO = null;

            int rst = 0;

            if (postMaster.getOperateFlag() == null || postMaster.getOperateFlag() == 0) {

                if (postMaster.getPostMasterId() == null || postMaster.getEmail() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "主贴id 或者 操做人邮箱 不能为空");
                    return rstJson.toString();
                }

                // 设置删除标识
                postMaster.setDeleteFlag(NumberUtils.BYTE_ZERO);

                resPostMasterDTO = postMasterService.retrieveByPostMasterId(postMaster.getPostMasterId());
                // 如果是置顶帖，判断是否能恢复
                /*
                 * int count = 0; if (resPostMasterDTO != null) { if
				 * (resPostMasterDTO.getPostTop() == 1) { if
				 * (resPostMasterDTO.getAlbumChildId() != null &&
				 * resPostMasterDTO.getAlbumChildId() > 0) { count =
				 * postMasterService.getTopCountByChild(resPostMasterDTO.
				 * getAlbumChildId()); } else if
				 * (resPostMasterDTO.getAlbumParentId() != null) { count =
				 * postMasterService.getTopCountByParent(resPostMasterDTO.
				 * getAlbumParentId()); } } }
				 * 
				 * if (count >= 5) { rstJson.put("rs",
				 * DictConstants.API_RES_CODE_ERROR); rstJson.put("rsdesp",
				 * "置顶帖最多为五条，请先删除原有的置顶帖！"); return rstJson.toString(); }
				 */
                postMaster.setPostTop((byte) 0);
                // 更新主贴信息
                rst = postMasterService.modifyMasterDeleteFlag(postMaster);
                if (rst == 0) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "恢复主贴失败");
                    return rstJson.toString();
                } else {
                    if (resPostMasterDTO == null) {
                        return rstJson.toString();
                    }

                    Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                    Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                    if (albumParentId == null) {
                        albumParentId = 0;
                    }

                    if (albumChildId == null) {
                        albumChildId = 0;
                    }

                    albumCache.updateAlbumMasterTotalNum(albumParentId, 0, 1);
                    albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                    if (!albumChildId.equals(0)) {
                        albumCache.updateAlbumMasterTotalNum(0, albumChildId, 1);
                        albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                    }
                }
            } else {

                if (postMaster.getPostMasterIds() == null || postMaster.getEmail() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "主贴id集合 或者 操做人邮箱 不能为空");
                    return rstJson.toString();
                }

                List<Integer> postMasterIds = postMaster.getPostMasterIds();
                if (!postMasterIds.isEmpty()) {
                    for (Integer postMasterId : postMasterIds) {
                        postMaster.setPostMasterId(postMasterId);

                        postMaster.setPostTop((byte) 0);
                        // 设置删除标识
                        postMaster.setDeleteFlag(NumberUtils.BYTE_ZERO);

                        resPostMasterDTO = postMasterService.retrieveByPostMasterId(postMaster.getPostMasterId());

                        // 更新主贴信息
                        rst = postMasterService.modifyMasterDeleteFlag(postMaster);

                        if (rst == 0) {
                            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                            rstJson.put("rsdesp", "恢复主贴失败");
                            return rstJson.toString();
                        } else {
                            if (resPostMasterDTO == null) {
                                return rstJson.toString();
                            }

                            Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                            Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                            if (albumParentId == null) {
                                albumParentId = 0;
                            }

                            if (albumChildId == null) {
                                albumChildId = 0;
                            }

                            albumCache.updateAlbumMasterTotalNum(albumParentId, 0, 1);
                            albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                            if (!albumChildId.equals(0)) {
                                albumCache.updateAlbumMasterTotalNum(0, albumChildId, 1);
                                albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "恢复主贴失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("deletePostMasterByManager(String jsonStr={})	--end", jsonStr);

        return rstJson.toString();
    }

    @Override
    public String modifyPostMasterByUserId(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("modifyPostByUserId(String jsonStr = {})	--start", jsonStr);
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMaster = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMaster = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            // 用户id 为空 | 主贴内容为空
            if (postMaster.getUserId() == null || (StringUtils.isBlank(postMaster.getContent())
                    && postMaster.getExternalLinks().compareTo(NumberUtils.BYTE_ZERO) == 0)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "userId, content | externalLinks 不能为空");
                return rstJson.toString();
            }

            int rst = postMasterService.modifyContent(postMaster);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "更新主贴内容失败");
                return rstJson.toString();
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新主贴内容失败" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("modifyPostByUserId(String jsonStr = {})	--end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String sendPostSlaveByUserId(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("modifyPostByUserId(String jsonStr = {})	--start", jsonStr);
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO reqPostSlaveDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqPostSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            if (reqPostSlaveDTO.getUserId() == null || reqPostSlaveDTO.getPostMasterId() == null
                    || (StringUtils.isBlank(reqPostSlaveDTO.getContent())
                    && reqPostSlaveDTO.getExternalLinks().compareTo(NumberUtils.BYTE_ZERO) == 0)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "userId,postMasterId,content | externalLinks 不能为空");
                return rstJson.toString();
            }
            // 校验用户是否被禁言
            if (checkPermissionsUtils.checkBannedByUserId(reqPostSlaveDTO.getUserId())) {

                //禁言
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "您已被管理员禁言");

                return rstJson.toString();

            }
            /*ReqUserPermissionInfoDTO userPermissionInfoDTO = new ReqUserPermissionInfoDTO();
            userPermissionInfoDTO.setUserId(reqPostSlaveDTO.getUserId());
            userPermissionInfoDTO.setBanCode("speech");
            List<ReqUserPermissionInfoDTO> tmpList = permissionService.getPermissionByUserId(userPermissionInfoDTO);

            if (tmpList != null && tmpList.size() > 0) {
                throw new BaseException(ExceptionCategory.Business_Privilege, "用户没有发帖权限");
            }*/
            if (reqPostSlaveDTO.getContent() != null && "null".equals(reqPostSlaveDTO.getContent())) {

                reqPostSlaveDTO.setContent(StringUtil.dealWithNull(reqPostSlaveDTO.getContent()));
            }

            int rst = postSlaveService.savePostSlave(reqPostSlaveDTO);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "发表回帖失败！");
                return rstJson.toString();
            } else {
                ResPostMasterDTO resPostMasterDTO = postMasterService
                        .retrieveByPostMasterId(reqPostSlaveDTO.getPostMasterId());

                if (resPostMasterDTO == null) {
                    return rstJson.toString();
                }

                Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                if (albumParentId == null) {
                    albumParentId = 0;
                }

                if (albumChildId == null) {
                    albumChildId = 0;
                }

                // update t_post_master表中的user_reply_count字段
                int postMasterId = reqPostSlaveDTO.getPostMasterId();
                int userId = reqPostSlaveDTO.getUserId();

                postMasterService.addUserReplyCount(postMasterId, userId, 0);
                postMasterService.addReplyCount(reqPostSlaveDTO.getPostMasterId());
                // postMasterService.updateReplyCount(reqPostSlaveDTO.getPostMasterId());
                albumCache.updateAlbumLastPostTime(albumParentId, 0, new Date());
                albumCache.updateAlbumReplyTotalNum(albumParentId, 0, 1);
                albumCache.updateAlbumNewPostNum(albumParentId, 0, 1);

                if (!albumChildId.equals(0)) {
                    albumCache.updateAlbumLastPostTime(0, albumChildId, new Date());
                    albumCache.updateAlbumReplyTotalNum(0, albumChildId, 1);
                    albumCache.updateAlbumNewPostNum(0, albumChildId, 1);
                }

                ReqScoreRecordDTO reqScoreRecordDTO = new ReqScoreRecordDTO();
                reqScoreRecordDTO.setUserId(reqPostSlaveDTO.getUserId());
                reqScoreRecordDTO.setRelId(rst);
                reqScoreRecordDTO.setN((double) 0);
                reqScoreRecordDTO.setRuleCode(DictConstants.SHEQU_SEND_REPLY_POST);
                reqScoreRecordDTO.setRuleType(DictConstants.RULETYPE);
                reqScoreRecordDTO
                        .setChannelSource(postMasterService.getChannelScource(reqPostSlaveDTO.getOsVersion()));

                if (resPostMasterDTO.getIsReplySlave() == 0) {
                    //如果是抢沙发 则通知个人中心奖励尚德元
                    reqScoreRecordDTO.setRuleCode(DictConstants.SHEQU_TAKE_SOFA);
                    // 发跟帖更新主贴表表中新加的is_reply_slave字段
                    postMasterService.updateReplySlave(reqPostSlaveDTO.getPostMasterId());
                }
                reqScoreRecordDTO.setEncryptStr(MD5.getAddScoreRecordValue(reqScoreRecordDTO.getUserId(),
                        reqScoreRecordDTO.getRuleCode(), reqScoreRecordDTO.getChannelSource()));
                String data = scoreSystemService.packagingAddScoreRecordData(reqScoreRecordDTO);

                try {

                    String result = scoreSystemService.getResponse(personalCenterURL + DictConstants.ADD_SCORE_PATH,
                            data);
                    String rsdesp = scoreSystemService.getAddScoreRecordRemark(result);
                    if (rsdesp != null) {
                        rstJson.put("rsdesp", rsdesp);
                    }

                } catch (UnsupportedEncodingException e) {
                    logger.debug("行为: 发跟贴, 请求个人中心(增加积分记录接口)失败!");
                }
                if (userId != resPostMasterDTO.getUserId()) {
                    ResPostSlaveDTO resPostSlave = postSlaveService.retrieveByPostSlaveId(rst);
                    String content = resPostSlave.getUserNickname() + "回复了您的帖子";
                    miPushService.pushMyReply(resPostMasterDTO.getUserId(), postMasterId, rst, null, content, "MY_REPLY");
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "发表回帖失败！" + e.getMessage());
            return rstJson.toString();
        }
        logger.debug("modifyPostByUserId(String jsonStr = {})	--end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String deletePostSlaveByUserId(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("deletePostSlaveByUserId(String jsonStr = {})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        PostSlave postSlave = null;
        ResPostSlaveDTO resPostSlaveDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlave = JSON.parseObject(jsonStr, PostSlave.class);

            if (postSlave.getPostSlaveId() == null || postSlave.getUserId() == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "userId, postSlaveId不能为空");
                return rstJson.toString();
            }

            resPostSlaveDTO = postSlaveService.retrieveByPostSlaveId(postSlave.getPostSlaveId());

            postSlave.setDeleteFlag(NumberUtils.BYTE_ONE);
            int rst = postSlaveService.update(postSlave);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "删除回帖失败！");
                return rstJson.toString();
            } else {
                if (resPostSlaveDTO == null) {
                    return rstJson.toString();
                }

                // postMasterService.reduceReplyCount(postSlave.getPostMasterId());
                postMasterService.reduceUserReplyCount(resPostSlaveDTO.getPostMasterId(), postSlave.getUserId(), 0);
                postMasterService.reduceReplyCount(resPostSlaveDTO.getPostMasterId());
                Integer albumParentId = resPostSlaveDTO.getAlbumParentId();
                Integer albumChildId = resPostSlaveDTO.getAlbumChildId();

                if (albumParentId == null) {
                    albumParentId = 0;
                }

                if (albumChildId == null) {
                    albumChildId = 0;
                }

                albumCache.updateAlbumReplyTotalNum(albumParentId, 0, -1);
                albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                if (!albumChildId.equals(0)) {
                    albumCache.updateAlbumReplyTotalNum(0, albumChildId, -1);
                    albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                }
            }
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除回帖失败！" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("deletePostSlaveByUserId(String jsonStr = {})	--end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String modifyPostSlaveByUserId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("modifyPostSlaveByUserId(String jsonStr = {})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlave = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlave = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            if (postSlave.getPostSlaveId() == null || postSlave.getUserId() == null
                    || (StringUtils.isBlank(postSlave.getContent())
                    && postSlave.getExternalLinks().compareTo(NumberUtils.BYTE_ZERO) == 0)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "postSlaveId, userId, content | externalLinks不能为空");
                return rstJson.toString();
            }

            int rst = postSlaveService.modifyContent(postSlave);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "更新回帖内容失败!");
                return rstJson.toString();
            }
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新回帖内容失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("modifyPostSlaveByUserId(String jsonStr = {})	--end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String sendReplyByUserId(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("sendReplyByUserId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        PostReply postReply = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReply = JSON.parseObject(jsonStr, PostReply.class);

            if (postReply.getPostMasterId() == null || postReply.getPostSlaveId() == null
                    || postReply.getUserId() == null || StringUtils.isBlank(postReply.getContent())) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "postMasterId, postSlaveId, userId, content 不能为空");
                return rstJson.toString();
            }

            // 校验用户是否被禁言//add by hurw 20160707
            if (checkPermissionsUtils.checkBannedByUserId(postReply.getUserId())) {

                //禁言
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "您已被管理员禁言");

                return rstJson.toString();

            }
            
           /* ReqUserPermissionInfoDTO userPermissionInfoDTO = new ReqUserPermissionInfoDTO();
            userPermissionInfoDTO.setUserId(postReply.getUserId());
            userPermissionInfoDTO.setBanCode("speech");
            List<ReqUserPermissionInfoDTO> tmpList = permissionService.getPermissionByUserId(userPermissionInfoDTO);

            if (tmpList != null && tmpList.size() > 0) {
                throw new BaseException(ExceptionCategory.Business_Privilege, "用户没有发帖权限");
            }*/
            if (postReply.getContent() != null && "null".equals(postReply.getContent())) {

                postReply.setContent(StringUtil.dealWithNull(postReply.getContent()));
            }

            int rst = postReplyService.insert(postReply);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "保存回复失败!");
                return rstJson.toString();
            } else {
                ResPostMasterDTO resPostMasterDTO = postMasterService
                        .retrieveByPostMasterId(postReply.getPostMasterId());
                if (resPostMasterDTO != null) {
                    Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                    Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                    if (albumParentId == null) {
                        albumParentId = 0;
                    }

                    if (albumChildId == null) {
                        albumChildId = 0;
                    }
                    if (resPostMasterDTO.getIsReplySlave() == 0) {
                        // 当发回复的时候通过主贴id 将主贴表中的is_reply_slave字段置为1
                        postMasterService.updateReplySlave(postReply.getPostMasterId());
                    }
                    final int postMasterId = postReply.getPostMasterId();
                    final int userId = postReply.getUserId();

                    postMasterService.addUserReplyCount(postMasterId, userId, 1);
                    postMasterService.addReplyCount(postMasterId);
                    // postMasterService.updateReplyCount(postReply.getPostMasterId());
                    albumCache.updateAlbumLastPostTime(albumParentId, 0, new Date());
                    albumCache.updateAlbumReplyTotalNum(albumParentId, 0, 1);

                    if (!albumChildId.equals(0)) {
                        albumCache.updateAlbumLastPostTime(0, albumChildId, new Date());
                        albumCache.updateAlbumReplyTotalNum(0, albumChildId, 1);
                    }
                }

                try {
                    ReqScoreRecordDTO reqScoreRecordDTO = new ReqScoreRecordDTO();
                    reqScoreRecordDTO.setUserId(postReply.getUserId());
                    reqScoreRecordDTO.setRelId(rst);
                    reqScoreRecordDTO.setN((double) 0);
                    reqScoreRecordDTO.setRuleCode(DictConstants.SHEQU_SEND_REPLY_POST);
                    reqScoreRecordDTO.setRuleType(DictConstants.RULETYPE);
                    reqScoreRecordDTO.setChannelSource(postMasterService.getChannelScource(postReply.getOsVersion()));
                    reqScoreRecordDTO.setEncryptStr(MD5.getAddScoreRecordValue(reqScoreRecordDTO.getUserId(),
                            reqScoreRecordDTO.getRuleCode(), reqScoreRecordDTO.getChannelSource()));
                    String data = scoreSystemService.packagingAddScoreRecordData(reqScoreRecordDTO);
                    String result = scoreSystemService.getResponse(personalCenterURL + DictConstants.ADD_SCORE_PATH,
                            data);
                    String rsdesp = scoreSystemService.getAddScoreRecordRemark(result);
                    if (rsdesp != null) {
                        rstJson.put("rsdesp", rsdesp);
                    }
                } catch (UnsupportedEncodingException e) {
                    logger.debug("行为: 回复, 请求个人中心(增加积分记录接口)失败!");
                }
                Integer replyToUserId = null;
                if (postReply.getReplyToUserId() != null) {//二级回复
                    replyToUserId = postReply.getReplyToUserId();
                } else {
                    ResPostSlaveDTO resPostSlave = postSlaveService.retrieveByPostSlaveId(postReply.getPostSlaveId());
                    replyToUserId = resPostSlave.getUserId();
                }
                if (postReply.getUserId() != replyToUserId) {
                    ResPostReplyDTO resPostReply = postReplyService.retrieveByReplyId(rst);
                    String content = resPostReply.getUserNickname() + "回复了您的帖子";
                    miPushService.pushMyReply(replyToUserId, resPostMasterDTO.getPostMasterId(), postReply.getPostSlaveId(), rst, content, "MY_REPLY");
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "保存回复失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("sendReplyByUserId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String deleteReplyByUserId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("deleteReplyByUserId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        PostReply postReply = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReply = JSON.parseObject(jsonStr, PostReply.class);

            // 设置删除标识
            postReply.setDeleteFlag(NumberUtils.BYTE_ONE);

            if (postReply.getReplyId() == null || postReply.getUserId() == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "userId, replyId 不能为空");
                return rstJson.toString();
            }

            int rst = postReplyService.update(postReply);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "删除回复失败!");
                return rstJson.toString();
            } else {
                ResPostMasterDTO resPostMasterDTO = postMasterService
                        .retrieveByPostMasterId(postReply.getPostMasterId());

                // postMasterService.reduceReplyCount(postReply.getPostMasterId());

                postMasterService.reduceUserReplyCount(postReply.getPostMasterId(), postReply.getUserId(), 1);

                postMasterService.reduceReplyCount(postReply.getPostMasterId());
                if (resPostMasterDTO != null) {
                    Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                    Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                    if (albumParentId == null) {
                        albumParentId = 0;
                    }

                    if (albumChildId == null) {
                        albumChildId = 0;
                    }

                    albumCache.updateAlbumReplyTotalNum(albumParentId, 0, -1);

                    if (!albumChildId.equals(0)) {
                        albumCache.updateAlbumReplyTotalNum(0, albumChildId, -1);
                    }
                }
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除回复失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("deleteReplyByUserId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String modifyReplyByUserId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("modifyReplyByUserId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        PostReply postReply = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReply = JSON.parseObject(jsonStr, PostReply.class);

            if (postReply.getReplyId() == null || postReply.getUserId() == null
                    || StringUtils.isBlank(postReply.getContent())) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "replyId, userId, content 不能为空");
                return rstJson.toString();
            }

            int rst = postReplyService.update(postReply);

            if (rst == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "更新回复失败!");
                return rstJson.toString();
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新回复失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("modifyReplyByUserId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String getReplyByPostSlaveId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getReplyByPostId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostReplyDTO postReply = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReply = JSON.parseObject(jsonStr, ReqPostReplyDTO.class);

            if (postReply.getPostSlaveId() == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "postSlaveId 不能为空");
                return rstJson.toString();
            }

            BaseSearchResultDTO<ResPostReplyDTO> rstList = postReplyService.retrieveByPostSlaveId(postReply);
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONObject.fromObject(rstList, jsonConfig));

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新回复失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("getReplyByPostId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostMasterById(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostMasterById(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            ResPostMasterDTO postMaster = postMasterService.retrieveByPostMasterId(postMasterDTO.getPostMasterId(),
                    postMasterDTO.getUserId());

            // PC端mobileText置空，APP端richText置空
            if ("PC".equals(postMasterDTO.getOsVersion())) {
                postMaster.setMobileText(null);
            } else {
                postMaster.setRichText(null);
            }

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONObject.fromObject(postMaster, jsonConfig));

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取主贴失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrievePostMasterById(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostSlaveByMasterId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostSlaveByMasterId(String jsonStr ={})	-- start", jsonStr);
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            ReqPostSlaveDTO postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            BaseSearchResultDTO<ResPostSlaveDTO> result = postSlaveService.retrieveByPostMasterId(postSlaveDTO);

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());

            JSONObject obj = JSONObject.fromObject(result, jsonConfig);

            // pc端查询主贴信息
            ResPostMasterDTO postMaster = null;
            if (postSlaveDTO != null && "PC".equals(postSlaveDTO.getOsVersion()) && postSlaveDTO.getPageNo() == 1) {
                ReqPostMasterDTO masterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

                if (masterDTO != null && masterDTO.getPostMasterId() != null) {
                    postMaster = postMasterService.retrieveByPostMasterId(masterDTO.getPostMasterId(),
                            postSlaveDTO.getUserId());
                }
                obj.put("postMaster", JSONObject.fromObject(postMaster, jsonConfig));
            }

            rstJson.put("resultMessage", obj);
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取跟帖失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrievePostSlaveByMasterId(String jsonStr ={})	-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostListByAlbumParentId(String jsonStr, String versionStr, String tokenStr) {
        logger.info("retrievePostListByAlbumParentId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getAlbumParentId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "主版id不能为空");
            }

            postMasterDTO.setAlbumChildId(0);

            Statistics stats = Statistics.newInstance("ApiPostServiceImpl.retrievePostListByAlbumParentId");

            // 先判断该主版是否隐藏，如果隐藏了，就直接返回
            int isInner = albumAdminService.getIsHideAlbumParent(postMasterDTO.getAlbumParentId());
            // 该主版面隐藏
            if (isInner == 1) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "该主版页面已经过时！");
                return rstJson.toString();

            }

            //1-step
            stats.mark();

            List<ResPostMasterDTO> hidePosts = null;

            if (postMasterDTO.getPostTop() == 1) {
                postMasterDTO.setUserId(null);
            }

            // 如果user_id不为空,且查询第一页列表时,查询用户屏蔽帖子列表
            if (StringUtils.isEmpty(postMasterDTO.getMasterType())) {
                if (checkQueryHidePosts(postMasterDTO)) {
                    hidePosts = postMasterService.retrieveHidePostsByUserAndAlbum(postMasterDTO);

                }
            }

            //2-step
            stats.mark();

            // 根据主版id获取帖子列表
            // postMasterDTO.setUserId(null);
            postMasterDTO.setDeleteFlag(NumberUtils.BYTE_ZERO);
            postMasterDTO.setIsHide(NumberUtils.BYTE_ZERO);
            BaseSearchResultDTO<ResPostMasterDTO> result = postMasterService.retrievePostListByCondition(postMasterDTO);

            //3-step
            stats.mark();

            if (hidePosts != null && hidePosts.size() > 0) {
                result.getResultList().addAll(hidePosts);
            }
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            jsonObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMasterDTO.getReqTime()));
            jsonObject.put("masterTypes",
                    postMasterService.getMasterTypesByAlbumParentId(postMasterDTO.getAlbumParentId()));
            rstJson.put("resultMessage", jsonObject);

            //4-step
            stats.end(200);
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据主版id查询出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrievePostListByAlbumParentId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostListByAlbumChildId(String jsonStr, String versionStr, String tokenStr) {
        logger.info("retrievePostListByAlbumChildId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getAlbumChildId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "子版id不能为空");
            }

            Statistics stats = Statistics.newInstance("ApiPostServiceImpl.retrievePostListByAlbumChildId");
            // 判断该子版是否隐藏
            // 先判断该主版是否隐藏，如果隐藏了，就直接返回
            int isInner = albumAdminService.getIsHideAlbumChild(postMasterDTO.getAlbumChildId());
            // 该主版面隐藏
            if (isInner == 1) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "该子版页面已经过时！");
                return rstJson.toString();

            }

            //1-step
            stats.mark();

            List<ResPostMasterDTO> hidePosts = null;

            if (postMasterDTO.getPostTop() == 1) {
                postMasterDTO.setUserId(null);
            }

            // 如果user_id不为空,且查询第一页列表时,查询用户屏蔽帖子列表
            if (StringUtils.isEmpty(postMasterDTO.getMasterType())) {
                if (checkQueryHidePosts(postMasterDTO)) {
                    hidePosts = postMasterService.retrieveHidePostsByUserAndAlbum(postMasterDTO);
                }
            }

            //2-step
            stats.mark();

            // 根据子版id获取帖子列表
            // postMasterDTO.setUserId(null);
            postMasterDTO.setIsHide(NumberUtils.BYTE_ZERO);
            postMasterDTO.setDeleteFlag(NumberUtils.BYTE_ZERO);
            BaseSearchResultDTO<ResPostMasterDTO> result = postMasterService.retrievePostListByCondition(postMasterDTO);

            //3-step
            stats.mark();
            if (hidePosts != null && hidePosts.size() > 0) {
                result.getResultList().addAll(hidePosts);
            }

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());

            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            jsonObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMasterDTO.getReqTime()));
            jsonObject.put("masterTypes",
                    postMasterService.getMasterTypesByAlbumChildId(postMasterDTO.getAlbumChildId()));
            rstJson.put("resultMessage", jsonObject);

            //4-step
            stats.end(200);
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据子版id查询出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrievePostListByAlbumChildId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostList(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "子版id不能为空");
            }

            if (postMasterDTO.getIsHide() != 0 && postMasterDTO.getDeleteFlag() == 1) {
                postMasterDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postMasterDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }
            //电话号模糊查询
            if (StringUtils.isNotEmpty(postMasterDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postMasterDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postMasterDTO.setUserIdList(userIdList);
                }
            }
            postMasterDTO.setIsHide(null);
            postMasterDTO.setDeleteFlag(null);
            BaseSearchResultDTO<ResPostMasterDTO> result = managerPostService
                    .retrieveBackGroundPostListByCondition(postMasterDTO);

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            jsonObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMasterDTO.getReqTime()));
            rstJson.put("resultMessage", jsonObject);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "管理后台查询帖子列表出错!" + e.getMessage());
            return rstJson.toString();
        }
        logger.debug("retrievePostList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostListByUserId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostListByUserId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getUserId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "用户id不能为空");
            }
            // 如果用户查看自己发的帖子，则屏蔽的要展示，删除的就不要展示了

            if (postMasterDTO.getLoginUserId() != null
                    && postMasterDTO.getLoginUserId().equals(postMasterDTO.getUserId())) {
                postMasterDTO.setDeleteFlag(NumberUtils.BYTE_ZERO);
                postMasterDTO.setIsHide(null);
            } else {
                // 如果用户查看别人列表的帖子，屏蔽的和删除的不需要展示
                postMasterDTO.setDeleteFlag(NumberUtils.BYTE_ZERO);
                postMasterDTO.setIsHide(NumberUtils.BYTE_ZERO);

            }

            BaseSearchResultDTO<ResPostMasterDTO> result = postMasterService.retrievePostListByUserId(postMasterDTO);
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());

            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            jsonObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMasterDTO.getReqTime()));
            rstJson.put("resultMessage", jsonObject);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据子版id查询出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrievePostListByUserId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrievePostSlaveById(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostSlaveById(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlaveDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            if (postSlaveDTO == null || postSlaveDTO.getPostSlaveId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "跟帖id不能为空");
            }

            ResPostSlaveDTO rst = postSlaveService.retrieveByPostSlaveId(postSlaveDTO);

            // PC端mobileText置空，APP端richText置空
            if ("PC".equals(postSlaveDTO.getOsVersion())) {
                rst.setMobileText(null);
            } else {
                rst.setRichText(null);
            }

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONObject.fromObject(rst, jsonConfig));

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据跟帖id查询出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrievePostSlaveById(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostMasterStar(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostMasterStar(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO.getOperateFlag() == null || postMasterDTO.getOperateFlag() == 0) {

                // 单条操作
                if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null
                        || postMasterDTO.getPostStar() == null) {
                    throw new BaseException(ExceptionCategory.Business_Query, "主贴id / 加精状态 不能为空");
                }

                postMasterService.updateState(postMasterDTO);

            } else {

                if (postMasterDTO == null || postMasterDTO.getPostMasterIds() == null
                        || postMasterDTO.getPostStar() == null) {
                    throw new BaseException(ExceptionCategory.Business_Query, "主贴id集合 / 加精状态 不能为空");
                }

                List<Integer> postMasterIds = postMasterDTO.getPostMasterIds();
                if (!postMasterIds.isEmpty()) {
                    for (Integer postMasterId : postMasterIds) {
                        postMasterDTO.setPostMasterId(postMasterId);
                        postMasterService.updateState(postMasterDTO);
                    }
                }
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "修改主贴加精状态失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostMasterStar(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostMasterGlobal(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostMasterGlobal(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null
                    || postMasterDTO.getPostGlobal() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "主贴id / 全局状态 不能为空");
            }

            postMasterService.updateState(postMasterDTO);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "修改主贴全局状态失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostMasterGlobal(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostMasterTop(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostMasterTop(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null
                    || postMasterDTO.getPostTop() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "主贴id / 置顶状态 不能为空");
            }

            int count = 0;
            if (postMasterDTO.getPostTop() == 1) {
                if (postMasterDTO.getAlbumChildId() != null && postMasterDTO.getAlbumChildId() > 0) {
                    count = postMasterService.getTopCountByChild(postMasterDTO.getAlbumChildId());
                } else if (postMasterDTO.getAlbumParentId() != null) {
                    count = postMasterService.getTopCountByParent(postMasterDTO.getAlbumParentId());
                }
            }
            if (count >= 5) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "普通置顶帖在一个主版或者一个子版中最多为5条");
                return rstJson.toString();
            }

            postMasterService.updateState(postMasterDTO);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "修改主贴置顶状态失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostMasterTop(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String countPostByUserId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("countPostByUserId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getUserId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "用户id不能为空");
            }

            int countNum = postMasterService.countPostByUserId(postMasterDTO.getUserId());

            JSONObject json = new JSONObject();
            json.put("postCount", countNum);

            rstJson.put("resultMessage", json.toString());

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据用户id获取主贴总数失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("countPostByUserId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostMasterHideState(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostMasterHideState(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO.getOperateFlag() == null || postMasterDTO.getOperateFlag() == 0) {
                // 单条操作
                if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null) {
                    throw new BaseException(ExceptionCategory.Business_Query, "主贴id不能为空");
                }

                ResPostMasterDTO resPostMasterDTO = postMasterService
                        .retrieveByPostMasterId(postMasterDTO.getPostMasterId());
                // 如果是置顶帖，判断是否能恢复
                /*
                 * int count = 0; if (postMasterDTO.getIsHide() == 0) { if
				 * (resPostMasterDTO != null) { if
				 * (resPostMasterDTO.getPostTop() == 1) { if
				 * (resPostMasterDTO.getAlbumChildId() != null &&
				 * resPostMasterDTO.getAlbumChildId() > 0) { count =
				 * postMasterService.getTopCountByChild(resPostMasterDTO.
				 * getAlbumChildId()); } else if
				 * (resPostMasterDTO.getAlbumParentId() != null) { count =
				 * postMasterService.getTopCountByParent(resPostMasterDTO.
				 * getAlbumParentId()); } } } } if (count >= 5) {
				 * rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
				 * rstJson.put("rsdesp", "置顶帖最多为五条，请先删除原有的置顶帖！"); return
				 * rstJson.toString(); }
				 */
                postMasterDTO.setPostTop((byte) 0);
                postMasterService.updateState(postMasterDTO);
            } else {
                // 批量操作
                if (postMasterDTO == null || postMasterDTO.getPostMasterIds() == null) {
                    throw new BaseException(ExceptionCategory.Business_Query, "主贴id不能为空");
                }
                List<Integer> postMasterIds = postMasterDTO.getPostMasterIds();

                for (Integer postMasterId : postMasterIds) {

                    postMasterDTO.setPostMasterId(postMasterId);

                    postMasterDTO.setPostTop((byte) 0);

                    postMasterService.updateState(postMasterDTO);
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新主贴屏蔽状态失败!主贴id : " + postMasterDTO.getPostMasterId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostMasterHideState(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    /**
     * 功能描述：检查是否满足查询用户屏蔽贴条件 满足以下条件 : 1. 已登录用户(PC) / 非游客(APP) 2. 查询主贴查询第一页列表时
     *
     * @param postMasterDTO
     * @return boolean
     * @author 李孱
     * @update:[变更日期YYYY-MM-DD][更改人姓名][变更描述]
     * @since 2016年7月14日
     */
    private boolean checkQueryHidePosts(ReqPostMasterDTO postMasterDTO) {
        return postMasterDTO != null && postMasterDTO.getUserId() != null && postMasterDTO.getUserId() > 0
                && (postMasterDTO.getPageNo() == null || postMasterDTO.getPageNo() == 1);
    }

    @Override
    public String getSofaPostMasterList(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getSofaPostMasterList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            BaseSearchResultDTO<ResPostMasterDTO> result = postMasterService
                    .retrieveSofaPostListByCondition(postMasterDTO);

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            rstJson.put("resultMessage", jsonObject);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "抢沙发接口 获取0回复0跟帖的主贴列表出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("getSofaPostMasterList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrieveMasterTypeById(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrieveMasterTypeById(String jsonStr ={})		-- start", jsonStr);
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "主贴id不能为空");
            }

            String result = postMasterService.getMasterTypesByPostMasterId(postMasterDTO.getPostMasterId());
            JSONObject json = new JSONObject();
            json.put("result", result);
            rstJson.put("resultMessage", json.toString());
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据主贴id获取帖子所属分类失败!主贴id : " + postMasterDTO.getPostMasterId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrieveMasterTypeById(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updateMasterType(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updateMasterType(String jsonStr ={})		-- start", jsonStr);
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null
                    || postMasterDTO.getAlbumParentId() == null || postMasterDTO.getMasterTypes() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "主贴id/主版id/帖子类型不能为空");
            }

            String masterTypes = postMasterService.getMasterTypesByPostMasterId(postMasterDTO.getPostMasterId());
            // 如果帖子原本有分类 则将删除置为1
            if (StringUtils.isNotEmpty(masterTypes)) {
                postMasterService.modifyMasterTypeDeleteFlag(postMasterDTO.getPostMasterId());
            }
            postMasterService.saveMasterType(postMasterDTO);
            postMasterService.updateState(postMasterDTO);
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据主贴id获取帖子所属分类失败!主贴id : " + postMasterDTO.getPostMasterId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updateMasterType(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String praiseByMasterId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("praiseByMasterId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterPraiseDTO reqPostMasterPraiseDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqPostMasterPraiseDTO = JSON.parseObject(jsonStr, ReqPostMasterPraiseDTO.class);

            if (reqPostMasterPraiseDTO == null || reqPostMasterPraiseDTO.getPostMasterId() == null
                    || reqPostMasterPraiseDTO.getIsPraise() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            int state = postMasterService.updatePraiseState(reqPostMasterPraiseDTO);
            ResPostMasterDTO postMasterDTO = postMasterService.getPostById(reqPostMasterPraiseDTO.getPostMasterId());

            if (state == -1) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                if(postMasterDTO!=null){
                	
                	albumCache.updateTotalPraiseCountByUserId(postMasterDTO.getUserId(), 1);
                }
                // rstJson.put("rsdesp", "取消点赞");
            }
            if (state == 1) {
            	if(postMasterDTO!=null){
            		
            		albumCache.updateTotalPraiseCountByUserId(reqPostMasterPraiseDTO.getUserId(), 0);
            	}
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                miPushService.pushMyPraise(reqPostMasterPraiseDTO.getPostUserId(), reqPostMasterPraiseDTO.getUserId(),
                        reqPostMasterPraiseDTO.getPostMasterId(), null);
                // rstJson.put("rsdesp", "点赞成功");
                String url = personalCenterURL + DictConstants.ADD_SCORE_PATH;
                String channelSource = postMasterService.getChannelScource(reqPostMasterPraiseDTO.getOsVersion());

                try {
                    ReqScoreRecordDTO reqScoreRecordDTO = new ReqScoreRecordDTO();
                    reqScoreRecordDTO.setUserId(reqPostMasterPraiseDTO.getUserId());
                    reqScoreRecordDTO.setRelId(reqPostMasterPraiseDTO.getPostMasterId());
                    reqScoreRecordDTO.setN((double) 0);
                    reqScoreRecordDTO.setRuleCode(DictConstants.SHEQU_LIKE_POST);
                    reqScoreRecordDTO.setRuleType(DictConstants.RULETYPE);
                    reqScoreRecordDTO.setChannelSource(channelSource);
                    reqScoreRecordDTO.setEncryptStr(MD5.getAddScoreRecordValue(reqScoreRecordDTO.getUserId(),
                            reqScoreRecordDTO.getRuleCode(), reqScoreRecordDTO.getChannelSource()));
                    String data = scoreSystemService.packagingAddScoreRecordData(reqScoreRecordDTO);
                    String result = scoreSystemService.getResponse(url, data);
                    String rsdesp = scoreSystemService.getAddScoreRecordRemark(result);
                    if (rsdesp != null) {
                        rstJson.put("rsdesp", rsdesp);
                    }
                } catch (UnsupportedEncodingException e) {
                    logger.debug("行为:主贴点赞 , 请求个人中心(增加积分记录接口)失败!");
                }

                scoreSystemService.userLiked(reqPostMasterPraiseDTO.getPostUserId(), reqPostMasterPraiseDTO.getPostMasterId(), url, channelSource);
            }
            if (state == 2) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                // rstJson.put("rsdesp", "你已经对该贴取消点赞了");
            }
            if (state == 3) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                // rstJson.put("rsdesp", "你已经对该贴点过赞了");
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "点赞主贴失败!主贴id : " + reqPostMasterPraiseDTO.getPostMasterId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("praiseByMasterId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String praiseBySlaveId(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("praiseByMasterId(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlavePraiseDTO reqPostSlavePraiseDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqPostSlavePraiseDTO = JSON.parseObject(jsonStr, ReqPostSlavePraiseDTO.class);

            if (reqPostSlavePraiseDTO == null || reqPostSlavePraiseDTO.getPostSlaveId() == null
                    || reqPostSlavePraiseDTO.getIsPraise() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            int state = postSlaveService.updatePraiseState(reqPostSlavePraiseDTO);
            Integer userId = postSlaveService.getUserIdBySlaveId(reqPostSlavePraiseDTO.getPostSlaveId());

            if (state == -1) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                if(userId!=null){
                	
                	albumCache.updateTotalPraiseCountByUserId(userId, 1);
                }
                // rstJson.put("rsdesp", "取消点赞");
            }
            if (state == 1) {
            	if(userId!=null){
            		
            		albumCache.updateTotalPraiseCountByUserId(userId, 0);
            	}
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                miPushService.pushMyPraise(reqPostSlavePraiseDTO.getSlaveUserId(), reqPostSlavePraiseDTO.getUserId(),
                        null, reqPostSlavePraiseDTO.getPostSlaveId());
                // rstJson.put("rsdesp", "点赞成功");
                String url = personalCenterURL + DictConstants.ADD_SCORE_PATH;
                String channelSource = postMasterService.getChannelScource(reqPostSlavePraiseDTO.getOsVersion());
                try {
                    ReqScoreRecordDTO reqScoreRecordDTO = new ReqScoreRecordDTO();
                    reqScoreRecordDTO.setUserId(reqPostSlavePraiseDTO.getUserId());
                    reqScoreRecordDTO.setRelId(reqPostSlavePraiseDTO.getPostSlaveId());
                    reqScoreRecordDTO.setN((double) 0);
                    reqScoreRecordDTO.setRuleCode(DictConstants.SHEQU_LIKE_POST);
                    reqScoreRecordDTO.setRuleType(DictConstants.RULETYPE);
                    reqScoreRecordDTO.setChannelSource(channelSource);
                    reqScoreRecordDTO.setEncryptStr(MD5.getAddScoreRecordValue(reqScoreRecordDTO.getUserId(),
                            reqScoreRecordDTO.getRuleCode(), reqScoreRecordDTO.getChannelSource()));
                    String data = scoreSystemService.packagingAddScoreRecordData(reqScoreRecordDTO);
                    String result = scoreSystemService.getResponse(url, data);
                    String rsdesp = scoreSystemService.getAddScoreRecordRemark(result);
                    if (rsdesp != null) {
                        rstJson.put("rsdesp", rsdesp);
                    }
                } catch (UnsupportedEncodingException e) {
                    logger.debug("行为:跟帖点赞, 请求个人中心(增加积分记录接口)失败!");
                }

                scoreSystemService.userLiked(reqPostSlavePraiseDTO.getSlaveUserId(), reqPostSlavePraiseDTO.getPostSlaveId(), url, channelSource);
            }
            if (state == 2) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                // rstJson.put("rsdesp", "你已经对该贴取消点赞了");
            }
            if (state == 3) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                // rstJson.put("rsdesp", "你已经对该贴点过赞了");
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "点赞跟贴失败!跟贴id : " + reqPostSlavePraiseDTO.getPostSlaveId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("praiseByMasterId(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String collectionMasterPost(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("collectionMasterPost(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostCollectionDTO reqPostCollectionDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqPostCollectionDTO = JSON.parseObject(jsonStr, ReqPostCollectionDTO.class);

            if (reqPostCollectionDTO == null || reqPostCollectionDTO.getPostMasterId() == null
                    || reqPostCollectionDTO.getUserId() == null || reqPostCollectionDTO.getIsCollection() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            int state = postMasterService.collectionMasterPost(reqPostCollectionDTO);

            if (state == -1) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                rstJson.put("rsdesp", "取消成功");
            }
            if (state == 1) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                rstJson.put("rsdesp", "收藏成功，可在我的收藏中查看");
            }

            if (state == 0) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                rstJson.put("rsdesp", "该帖子已被删除，无法收藏");
            }

            if (state == 2) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                rstJson.put("rsdesp", "你已经对该贴取消收藏了");
            }
            if (state == 3) {
                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                rstJson.put("rsdesp", "你已经收藏该帖子了");
            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "收藏主贴失败!主贴id : " + reqPostCollectionDTO.getPostMasterId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("collectionMasterPost(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String searchPostListBySubject(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("searchPostListBySubject(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostBySubjectDTO reqPostBySubjectDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqPostBySubjectDTO = JSON.parseObject(jsonStr, ReqPostBySubjectDTO.class);

            if (reqPostBySubjectDTO == null || reqPostBySubjectDTO.getSearchContent() == null
                    || org.apache.commons.lang3.StringUtils.isBlank(reqPostBySubjectDTO.getSearchContent())) {
                throw new BaseException(ExceptionCategory.Business_Query, "搜索框内容不能为空");
            }

            // 根据搜索框内容查询主贴

            BaseSearchResultDTO<ResPostMasterDTO> result = postMasterService
                    .retrieveCollectionPostListBySubject(reqPostBySubjectDTO);

            try {

                List<ResPostMasterDTO> postList = result.getResultList();

                //List<Integer> userIds = new ArrayList<Integer>();

                if (postList != null && !postList.isEmpty())

                    for (ResPostMasterDTO resPostMasterDTO : postList) {

                        if (resPostMasterDTO.getUserId() != null) {

                            Short isVip = checkIsVipClient.checkIsVip(resPostMasterDTO.getUserId());
                            if (isVip != null) {
                                resPostMasterDTO.setIsVip(isVip);
                            }

                        }

                        //userIds.add(resPostMasterDTO.getUserId());
                        //获取用户等级
                        String gradeUrl = personalCenterURL + DictConstants.USER_GRAGE_PATH;
                        JSONObject jsonParam = new JSONObject();
                        jsonParam.put("userId", resPostMasterDTO.getUserId());
                        jsonParam.put("encryptStr", MD5.getMD5tEncryptStr(resPostMasterDTO.getUserId()));
                        int gradeCode = gradeService.getGradeCode(gradeUrl, jsonParam);
                        resPostMasterDTO.setGradeCode(gradeCode);

                    }
                   /* //批量获取用户等级
                    Map<String, ResLevelMedalDTO> map = personalService.getUserLevelAndMedal(userIds);
                    for (ResPostMasterDTO resPostMasterDTO : postList) {
                    	ResLevelMedalDTO resLevel = map.get(resPostMasterDTO.getUserId());
                    	resPostMasterDTO.setGradeCode(resLevel.getGradeCode());
                    	resPostMasterDTO.setMedalList(resLevel.getMedalDTOList());
					}*/


            } catch (Exception e) {
                logger.debug("给搜索结果添加学员是否为vip失败");

            }

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            jsonObject.put("reqTime",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(reqPostBySubjectDTO.getReqTime()));

            rstJson.put("resultMessage", jsonObject);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据搜索框查询主贴出错!" + e.getMessage());
            return rstJson.toString();
        }
        logger.debug("searchPostListBySubject(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String importAllPost(String jsonStr) {
        logger.debug("importAllPost(String jsonStr ={})		-- start");

        JSONObject rstJson = new JSONObject();

        try {

            int i = postMasterService.importAllPost();

            if (i == 1) {

                rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
                rstJson.put("rsdesp", "一键导入成功");
                return rstJson.toString();

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "一键导入失败!" + e.getMessage());
            return rstJson.toString();
        }
        logger.debug("importAllPost(String jsonStr ={})		-- end");
        return rstJson.toString();
    }

    @Override
    public String getPostOperationLog(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("getPostOperationLog(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        try {
            Integer postMasterId = JSON.parseObject(jsonStr).getInteger("postMasterId");

            if (postMasterId == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }
            List<ResPostOperationLogDTO> resList = postOperationLogService.getPostOperationLog(postMasterId);
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONArray.fromObject(resList, jsonConfig));
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取主贴操作日志失败！" + e.getMessage());
        }
        logger.debug("getPostOperationLog	--end");

        return rstJson.toString();

    }

    @Override
    public String retrievePostPurpose(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostPurpose(String jsonStr ={})-- start", jsonStr);
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            Integer postMasterId = JSON.parseObject(jsonStr).getInteger("postMasterId");

            if (postMasterId == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            String result = postOperationLogService.retrievePostPurpose(postMasterId);
            JSONObject json = new JSONObject();
            json.put("result", result);
            rstJson.put("resultMessage", json.toString());
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据主贴id获取发帖动机失败!" + e.getMessage());
            return rstJson.toString();
        }
        logger.debug("retrievePostPurpose(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostMasterGlobalTop(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostMasterGlobalTop(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null
                    || postMasterDTO.getPostGlobalTop() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "主贴id / 全局置顶状态 不能为空");
            }
            postMasterService.updateState(postMasterDTO);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "修改主贴全局置顶状态失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostMasterGlobalTop(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String retrieveSlavePostList(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrieveSlavePostList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlaveDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            if (postSlaveDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postSlaveDTO.getIsHide() != 0 && postSlaveDTO.getDeleteFlag() == 1) {
                postSlaveDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postSlaveDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            // 电话号模糊查询
            if (StringUtils.isNotEmpty(postSlaveDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postSlaveDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postSlaveDTO.setUserIdList(userIdList);
                }
            }

            BaseSearchResultDTO<ResPostSlaveDTO> result = managerPostService
                    .retrieveBackGroundPostSlaveListByCondition(postSlaveDTO);

            JsonConfig jsonConfig = new JsonConfig();

            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);

            jsonObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postSlaveDTO.getReqTime()));

            rstJson.put("resultMessage", jsonObject);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "后端管理系统查询跟帖列表出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrieveSlavePostList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String slavePostDownload(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrieveSlavePostList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlaveDTO = new ReqPostSlaveDTO();
        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }
            postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            if (postSlaveDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postSlaveDTO.getIsHide() != 0 && postSlaveDTO.getDeleteFlag() == 1) {
                postSlaveDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postSlaveDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            // postSlaveDTO.setUserId(null);
            if (StringUtils.isNotEmpty(postSlaveDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postSlaveDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postSlaveDTO.setUserIdList(userIdList);
                }

            }
            BaseSearchResultDTO<ResPostSlaveDTO> result = managerPostService
                    .retrieveBackGroundPostSlaveListDownload(postSlaveDTO);

            List<Map<String, Object>> resultList = new LinkedList<Map<String, Object>>();
            List<ResPostSlaveDTO> list = result.getResultList();
            for (ResPostSlaveDTO slave : list) {
                String state = "正常";
                if (slave.getDeleteFlag() == 1 && slave.getIsHide() != 0) {
                    state = "已删除|已屏蔽";
                } else if (slave.getDeleteFlag() == 1) {
                    state = "已删除";
                } else if (slave.getIsHide() != 0) {
                    state = "已屏蔽";
                }
                slave.setPostState(state);
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("postMasterId", slave.getPostMasterId());
                map.put("postSlaveId", slave.getPostSlaveId());
                map.put("replyType", "一级回帖");
                map.put("content", slave.getContent());
                map.put("albumParentName", slave.getAlbumParentName());
                map.put("albumChildName", slave.getAlbumChildName());
                map.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(slave.getCreateTime()));
                map.put("userNickname", slave.getUserNickname());
                map.put("userMobile", slave.getUserMobile());
                map.put("userId", slave.getUserId());
                map.put("modifyTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(slave.getOperateTime()));
                map.put("operator", slave.getOperator());
                map.put("postState", slave.getPostState());
                resultList.add(map);
            }
            rstJson.put("resultMessage", resultList);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "后端管理系统查询跟帖列表出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrieveSlavePostList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @SuppressWarnings("unused")
    @Override
    public String dhPostSlaveDownload(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("dhPostSlaveDownload(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlaveDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            if (postSlaveDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postSlaveDTO.getIsHide() != 0 && postSlaveDTO.getDeleteFlag() == 1) {
                postSlaveDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postSlaveDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            if (StringUtils.isNotEmpty(postSlaveDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postSlaveDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postSlaveDTO.setUserIdList(userIdList);
                }
            }

            BaseSearchResultDTO<ResPostSlaveDTO> result = managerPostService
                    .retrieveHDBackGroundPostSlaveListByCondition(postSlaveDTO);

            List<Map<String, Object>> resultList = new LinkedList<Map<String, Object>>();
            List<ResPostSlaveDTO> list = result.getResultList();
            for (ResPostSlaveDTO slave : list) {
                String state = "";
                if (slave.getDeleteFlag() != null && slave.getDeleteFlag() == 1) {
                    state += state.length() > 0 ? "|已删除" : "已删除";
                }
                if (slave.getIsHide() != null && slave.getIsHide() == 1) {
                    state += state.length() > 0 ? "|人工屏蔽" : "人工屏蔽";
                }
                if (slave.getIsHide() != null && slave.getIsHide() == 2) {
                    state += state.length() > 0 ? "|系统屏蔽" : "系统屏蔽";
                }
                slave.setPostState(state);
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("postMasterId", slave.getPostMasterId());
                map.put("postSlaveId", slave.getPostSlaveId());
                map.put("replyType", "一级回帖");
                map.put("content", slave.getContent());
                map.put("albumParentName", slave.getAlbumParentName());
                map.put("albumChildName", slave.getAlbumChildName());
                map.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(slave.getCreateTime()));
                map.put("userNickname", slave.getUserNickname());
                map.put("userMobile", slave.getUserMobile());
                map.put("sensitiveWord", slave.getSensitiveWord());
                map.put("userId", slave.getUserId());
                map.put("modifyTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(slave.getOperateTime()));
                map.put("operator", slave.getOperator());
                map.put("postState", slave.getPostState());
                resultList.add(map);
            }
            rstJson.put("resultMessage", resultList);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", e.getMessage());
            return rstJson.toString();
        }

        logger.debug("dhPostSlaveDownload(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @SuppressWarnings("unused")
    @Override
    public String dhPostReplyDownload(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("dhPostReplyDownload(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostReplyDTO postReplyDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReplyDTO = JSON.parseObject(jsonStr, ReqPostReplyDTO.class);

            if (postReplyDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postReplyDTO.getIsHide() != 0 && postReplyDTO.getDeleteFlag() == 1) {
                postReplyDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postReplyDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            // postSlaveDTO.setUserId(null);
            if (StringUtils.isNotEmpty(postReplyDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postReplyDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postReplyDTO.setUserIdList(userIdList);
                }

            }

            BaseSearchResultDTO<ResPostReplyDTO> result = managerPostService
                    .retrieveHDBackGroundPostReplyListByCondition(postReplyDTO);

            List<Map<String, Object>> resultList = new LinkedList<Map<String, Object>>();
            List<ResPostReplyDTO> list = result.getResultList();
            for (ResPostReplyDTO reply : list) {
                String state = "";
                if (reply.getDeleteFlag() != null && reply.getDeleteFlag() == 1) {
                    state += state.length() > 0 ? "|已删除" : "已删除";
                }
                if (reply.getIsHide() != null && reply.getIsHide() == 1) {
                    state += state.length() > 0 ? "|人工屏蔽" : "人工屏蔽";
                }
                if (reply.getIsHide() != null && reply.getIsHide() == 2) {
                    state += state.length() > 0 ? "|系统屏蔽" : "系统屏蔽";
                }
                reply.setPostState(state);
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("postMasterId", reply.getPostMasterId());
                map.put("postReplyId", reply.getReplyId());
                map.put("replyType", "二级回帖");
                map.put("content", reply.getContent());
                map.put("albumParentName", reply.getAlbumParentName());
                map.put("albumChildName", reply.getAlbumChildName());
                map.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(reply.getCreateTime()));
                map.put("userNickname", reply.getUserNickname());
                map.put("userMobile", reply.getUserMobile());
                map.put("userId", reply.getUserId());
                map.put("sensitiveWord", reply.getSensitiveWord());
                map.put("modifyTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(reply.getOperateTime()));
                map.put("operator", reply.getOperator());
                map.put("postState", reply.getPostState());
                resultList.add(map);
            }
            rstJson.put("resultMessage", resultList);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", e.getMessage());
            return rstJson.toString();
        }

        logger.debug("dhPostReplyDownload(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String replyPostDownload(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrieveReplyPostList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostReplyDTO postReplyDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReplyDTO = JSON.parseObject(jsonStr, ReqPostReplyDTO.class);

            if (postReplyDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postReplyDTO.getIsHide() != 0 && postReplyDTO.getDeleteFlag() == 1) {
                postReplyDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postReplyDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            // postSlaveDTO.setUserId(null);

            // 判断mobile，不为空
            if (StringUtils.isNotEmpty(postReplyDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postReplyDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postReplyDTO.setUserIdList(userIdList);
                }

            }

            BaseSearchResultDTO<ResPostReplyDTO> result = managerPostService
                    .retrieveBackGroundPostReplyListDownload(postReplyDTO);

            List<Map<String, Object>> resultList = new LinkedList<Map<String, Object>>();
            List<ResPostReplyDTO> list = result.getResultList();
            for (ResPostReplyDTO reply : list) {
                String state = "正常";
                if (reply.getDeleteFlag() == 1 && reply.getIsHide() != 0) {
                    state = "已删除|已屏蔽";
                } else if (reply.getDeleteFlag() == 1) {
                    state = "已删除";
                } else if (reply.getIsHide() != 0) {
                    state = "已屏蔽";
                }
                reply.setPostState(state);
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("postMasterId", reply.getPostMasterId());
                map.put("postReplyId", reply.getReplyId());
                map.put("replyType", "二级回帖");
                map.put("content", reply.getContent());
                map.put("albumParentName", reply.getAlbumParentName());
                map.put("albumChildName", reply.getAlbumChildName());
                map.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(reply.getCreateTime()));
                map.put("userNickname", reply.getUserNickname());
                map.put("userMobile", reply.getUserMobile());
                map.put("userId", reply.getUserId());
                map.put("modifyTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(reply.getOperateTime()));
                map.put("operator", reply.getOperator());
                map.put("postState", reply.getPostState());
                resultList.add(map);
            }
            rstJson.put("resultMessage", resultList);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "后端管理系统查询回复列表出错!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrieveReplyPostList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostSlaveHideState(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostSlaveHideState(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlaveDTO = null;
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            int rst = 0;
            if (postSlaveDTO.getOperateFlag() == null || postSlaveDTO.getOperateFlag() == 0) {
                // 单条操作
                if (postSlaveDTO.getPostSlaveId() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "postSlaveId不能为空");
                    return rstJson.toString();
                }

                PostSlave postSlave = new PostSlave();
                BeanUtils.copyProperties(postSlaveDTO, postSlave);
                postSlave.setOperateTime(new Date());
                rst = postSlaveService.update(postSlave);

                if (rst > 0) {
                    String type = postSlaveOperationLogService.getSlaveOperationType(postSlaveDTO);

                    try {
                        ReqPostSlaveOperationLogDTO operationLog = new ReqPostSlaveOperationLogDTO(
                                postSlaveDTO.getPostSlaveId(), postSlaveDTO.getEmail(), postSlaveDTO.getPostPurpose(),
                                type);

                        postSlaveOperationLogService.savePostSlaveOperationLog(operationLog);
                        // 屏蔽跟帖
                        // 通过跟帖id 获取到该跟帖人的id
                        ResPostSlaveDTO resPostSlave = postSlaveService
                                .getPostSlaveByPostSlaveId(postSlaveDTO.getPostSlaveId());
                        if (postSlaveDTO.getIsHide() != 0) {

                            postMasterService.reduceUserReplyCount(resPostSlave.getPostMasterId(),
                                    resPostSlave.getUserId(), 0);

                            postMasterService.reduceReplyCount(resPostSlave.getPostMasterId());
                        } else {
                            // 恢复
                            postMasterService.addUserReplyCount(resPostSlave.getPostMasterId(),
                                    resPostSlave.getUserId(), 0);

                            postMasterService.addReplyCount(resPostSlave.getPostMasterId());
                        }
                    } catch (Exception e) {
                        logger.debug("保存跟帖状态操作日志失败:" + e.getMessage());
                    }
                }
            } else {
                // 批量操作
                if (postSlaveDTO.getPostSlaveIds() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "postSlaveId不能为空");
                    return rstJson.toString();
                }

                PostSlave postSlave = new PostSlave();

                List<Integer> postSlaveIds = postSlaveDTO.getPostSlaveIds();
                if (!postSlaveIds.isEmpty()) {

                    for (Integer postSlaveId : postSlaveIds) {
                        postSlaveDTO.setPostSlaveId(postSlaveId);
                        BeanUtils.copyProperties(postSlaveDTO, postSlave);
                        postSlave.setOperateTime(new Date());
                        rst = postSlaveService.update(postSlave);

                        if (rst > 0) {
                            String type = postSlaveOperationLogService.getSlaveOperationType(postSlaveDTO);

                            ReqPostSlaveOperationLogDTO operationLog = new ReqPostSlaveOperationLogDTO(
                                    postSlaveDTO.getPostSlaveId(), postSlaveDTO.getEmail(),
                                    postSlaveDTO.getPostPurpose(), type);

                            postSlaveOperationLogService.savePostSlaveOperationLog(operationLog);

                            // 屏蔽成功后
                            // 屏蔽跟帖
                            // 通过跟帖id 获取到该跟帖人的id
                            ResPostSlaveDTO resPostSlave = postSlaveService
                                    .getPostSlaveByPostSlaveId(postSlaveDTO.getPostSlaveId());
                            if (postSlaveDTO.getIsHide() != 0) {

                                postMasterService.reduceUserReplyCount(resPostSlave.getPostMasterId(),
                                        resPostSlave.getUserId(), 0);

                                postMasterService.reduceReplyCount(resPostSlave.getPostMasterId());
                            } else {
                                // 恢复
                                postMasterService.addUserReplyCount(resPostSlave.getPostMasterId(),
                                        resPostSlave.getUserId(), 0);

                                postMasterService.addReplyCount(resPostSlave.getPostMasterId());
                            }
                        }
                    }
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新跟帖屏蔽状态失败!跟贴id : " + postSlaveDTO.getPostSlaveId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostSlaveHideState(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostSlaveDeleteFlag(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostSlaveDeleteFlag(String jsonStr = {})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostSlaveDTO postSlaveDTO = null;
        ResPostSlaveDTO resPostSlaveDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postSlaveDTO = JSON.parseObject(jsonStr, ReqPostSlaveDTO.class);

            int rst = 0;
            if (postSlaveDTO.getOperateFlag() == null || postSlaveDTO.getOperateFlag() == 0) {

                if (postSlaveDTO.getPostSlaveId() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "postSlaveId不能为空");
                    return rstJson.toString();
                }

                resPostSlaveDTO = postSlaveService.retrieveByPostSlaveId(postSlaveDTO.getPostSlaveId());

                PostSlave postSlave = new PostSlave();
                BeanUtils.copyProperties(postSlaveDTO, postSlave);
                postSlave.setOperateTime(new Date());
                rst = postSlaveService.update(postSlave);

                if (rst == 0) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "删除回帖失败！");
                    return rstJson.toString();
                } else {
                    if (resPostSlaveDTO == null) {
                        return rstJson.toString();
                    }

                    Integer albumParentId = resPostSlaveDTO.getAlbumParentId();
                    Integer albumChildId = resPostSlaveDTO.getAlbumChildId();

                    if (albumParentId == null) {
                        albumParentId = 0;
                    }

                    if (albumChildId == null) {
                        albumChildId = 0;
                    }

                    albumCache.updateAlbumReplyTotalNum(albumParentId, 0, -1);
                    albumCache.updateAlbumNewPostNum(albumParentId, 0, -1);

                    if (!albumChildId.equals(0)) {
                        albumCache.updateAlbumReplyTotalNum(0, albumChildId, -1);
                        albumCache.updateAlbumNewPostNum(0, albumChildId, -1);
                    }

                    String type = postSlaveOperationLogService.getSlaveOperationType(postSlaveDTO);
                    try {
                        ReqPostSlaveOperationLogDTO operationLog = new ReqPostSlaveOperationLogDTO(
                                postSlaveDTO.getPostSlaveId(), postSlaveDTO.getEmail(), postSlaveDTO.getPostPurpose(),
                                type);

                        postSlaveOperationLogService.savePostSlaveOperationLog(operationLog);

                        // 屏蔽跟帖
                        // 通过跟帖id 获取到该跟帖人的id
                        ResPostSlaveDTO resPostSlave = postSlaveService
                                .getPostSlaveByPostSlaveId(postSlaveDTO.getPostSlaveId());
                        if (postSlaveDTO.getDeleteFlag() == 1) {

                            postMasterService.reduceUserReplyCount(resPostSlave.getPostMasterId(),
                                    resPostSlave.getUserId(), 0);

                            postMasterService.reduceReplyCount(resPostSlave.getPostMasterId());
                        } else {
                            // 恢复
                            postMasterService.addUserReplyCount(resPostSlave.getPostMasterId(),
                                    resPostSlave.getUserId(), 0);

                            postMasterService.addReplyCount(resPostSlave.getPostMasterId());
                        }

                    } catch (Exception e) {
                        logger.debug("保存跟帖状态操作日志失败:" + e.getMessage());
                    }
                }
            } else {
                // 批量操作
                if (postSlaveDTO.getPostSlaveIds() == null) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "postSlaveId不能为空");
                    return rstJson.toString();
                }

                List<Integer> postSlaveIds = postSlaveDTO.getPostSlaveIds();

                PostSlave postSlave = new PostSlave();

                if (!postSlaveIds.isEmpty()) {
                    for (Integer postSlaveId : postSlaveIds) {
                        resPostSlaveDTO = postSlaveService.retrieveByPostSlaveId(postSlaveId);

                        BeanUtils.copyProperties(postSlaveDTO, postSlave);
                        postSlave.setOperateTime(new Date());
                        postSlave.setPostSlaveId(postSlaveId);
                        rst = postSlaveService.update(postSlave);

                        if (rst > 0) {

                            String type = postSlaveOperationLogService.getSlaveOperationType(postSlaveDTO);

                            ReqPostSlaveOperationLogDTO operationLog = new ReqPostSlaveOperationLogDTO(
                                    postSlave.getPostSlaveId(), postSlaveDTO.getEmail(), postSlaveDTO.getPostPurpose(),
                                    type);

                            postSlaveOperationLogService.savePostSlaveOperationLog(operationLog);

                            // 屏蔽跟帖
                            // 通过跟帖id 获取到该跟帖人的id
                            ResPostSlaveDTO resPostSlave = postSlaveService
                                    .getPostSlaveByPostSlaveId(postSlave.getPostSlaveId());
                            if (postSlaveDTO.getDeleteFlag() == 1) {

                                postMasterService.reduceUserReplyCount(resPostSlave.getPostMasterId(),
                                        resPostSlave.getUserId(), 0);

                                postMasterService.reduceReplyCount(resPostSlave.getPostMasterId());
                            } else {
                                // 恢复
                                postMasterService.addUserReplyCount(resPostSlave.getPostMasterId(),
                                        resPostSlave.getUserId(), 0);

                                postMasterService.addReplyCount(resPostSlave.getPostMasterId());
                            }
                        }
                    }
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除回帖失败！" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostSlaveDeleteFlag(String jsonStr = {})	--end", jsonStr);
        return rstJson.toString();

    }

    @Override
    public String getPostSlaveOperationLog(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getPostSlaveOperationLog(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        try {
            Integer postSlaveId = JSON.parseObject(jsonStr).getInteger("postSlaveId");

            if (postSlaveId == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }
            List<ResPostSlaveOperationLogDTO> resList = postSlaveOperationLogService
                    .getPostSlaveOperationLog(postSlaveId);
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONArray.fromObject(resList, jsonConfig));
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取跟帖操作日志失败！" + e.getMessage());
        }
        logger.debug("getPostSlaveOperationLog	--end");

        return rstJson.toString();
    }

    @Override
    public String normalPostDownload(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqPostMasterDTO postMasterDTO = null;
        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postMasterDTO.getIsHide() != 0 && postMasterDTO.getDeleteFlag() == 1) {
                postMasterDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postMasterDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            postMasterDTO.setIsHide(null);
            postMasterDTO.setDeleteFlag(null);
            if (StringUtils.isNotEmpty(postMasterDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postMasterDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postMasterDTO.setUserIdList(userIdList);
                }
            }
            BaseSearchResultDTO<ResPostMasterDTO> result = managerPostService
                    .retrieveBackGroundPostListAndDownLoad(postMasterDTO);

            List<Map<String, Object>> resultList = new LinkedList<Map<String, Object>>();
            List<ResPostMasterDTO> list = result.getResultList();
            for (ResPostMasterDTO postMaster : list) {
                String state = "正常";
                if (postMaster.getPostGlobalTop() != null && postMaster.getPostGlobalTop() == 1) {
                    state += "|全局置顶";
                }
                if (postMaster.getPostTop() != null && postMaster.getPostTop() == 1) {
                    state += "|置顶";
                }
                if (postMaster.getPostStar() != null && postMaster.getPostStar() == 1) {
                    state += "|加精";
                }
                if (postMaster.getPostTabs() != null && !postMaster.getPostTabs().isEmpty()) {
                    state += postMaster.getPostTabs();
                }
                postMaster.setPostState(state);
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("postMasterId", postMaster.getPostMasterId());
                if (StringUtils.isNotBlank(postMaster.getPostSubject())) {
                    map.put("postSubject", postMaster.getPostSubject());
                } else {
                    if (postMaster.getContent().length() >= 10) {
                        map.put("postSubject", postMaster.getContent().substring(0, 10));
                    }
                    map.put("postSubject", postMaster.getContent());
                }
                map.put("praiseCount", postMaster.getPraiseCount());
                map.put("replyCount", postMaster.getReplyCount());
                map.put("albumParentName", postMaster.getAlbumParentName());
                map.put("albumChildName", postMaster.getAlbumChildName());
                map.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMaster.getCreateTime()));
                map.put("userNickname", postMaster.getUserNickname());
                map.put("userMobile", postMaster.getUserMobile());
                map.put("userId", postMaster.getUserId());
                map.put("modifyTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMaster.getOperateTime()));
                map.put("operator", postMaster.getOperator());
                map.put("postState", postMaster.getPostState());
                resultList.add(map);
            }
            rstJson.put("resultMessage", resultList);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "后端管理系统查询主贴列表失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrieveSlavePostList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();

    }

    @SuppressWarnings("unused")
    @Override
    public String dhPostDownload(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }

            // 获取帖子列表
            if (postMasterDTO.getIsHide() != 0 && postMasterDTO.getDeleteFlag() == 1) {
                postMasterDTO.setQueryForManage(NumberUtils.BYTE_ONE);
            } else {
                postMasterDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
            }

            postMasterDTO.setIsHide(null);
            postMasterDTO.setDeleteFlag(null);
            if (StringUtils.isNotEmpty(postMasterDTO.getMobile())) {
                List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postMasterDTO.getMobile());
                userIdList.add(-1);
                if (userIdList != null) {
                    postMasterDTO.setUserIdList(userIdList);
                }
            }

            BaseSearchResultDTO<ResPostMasterDTO> result = managerPostService
                    .retrieveHDBackGroundPostListByCondition(postMasterDTO);

            List<Map<String, Object>> resultList = new LinkedList<Map<String, Object>>();
            List<ResPostMasterDTO> list = result.getResultList();
            for (ResPostMasterDTO postMaster : list) {
                String state = "";
                if (postMaster.getDeleteFlag() != null && postMaster.getDeleteFlag() == 1) {
                    state += state.length() > 0 ? "|已删除" : "已删除";
                }
                if (postMaster.getIsHide() != null && postMaster.getIsHide() == 1) {
                    state += state.length() > 0 ? "|人工屏蔽" : "人工屏蔽";
                }
                if (postMaster.getIsHide() != null && postMaster.getIsHide() == 2) {
                    state += state.length() > 0 ? "|系统屏蔽" : "系统屏蔽";
                }
                if (postMaster.getPostGlobalTop() != null && postMaster.getPostGlobalTop() == 1) {
                    state += "|全局置顶";
                }
                if (postMaster.getPostTop() != null && postMaster.getPostTop() == 1) {
                    state += "|置顶";
                }
                if (postMaster.getPostStar() != null && postMaster.getPostStar() == 1) {
                    state += "|加精";
                }
                postMaster.setPostState(state);
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("postMasterId", postMaster.getPostMasterId());
                String chatContent = null;
                if (postMaster.getContent() != null) {
                    chatContent = postMaster.getContent().length() <= 500 ? postMaster.getContent()
                            : postMaster.getContent().substring(0, 500) + "...";
                }
                if (StringUtils.isNotBlank(postMaster.getPostSubject())) {
                    map.put("postSubject", postMaster.getPostSubject());
                } else {
                    if (postMaster.getContent().length() >= 10) {
                        map.put("postSubject", postMaster.getContent().substring(0, 10));
                    }
                    map.put("postSubject", postMaster.getContent());
                }
                map.put("content", chatContent);
                map.put("albumParentName", postMaster.getAlbumParentName());
                // 学员归属
                List<PostMasterAttribution> postMasterAttributions = postMasterService
                        .getAttributionByUserId(postMaster.getUserId());

                String attribution = "";
                if (postMasterAttributions != null && !postMasterAttributions.isEmpty()) {
                    for (PostMasterAttribution postMasterAttribution : postMasterAttributions) {
                        if (postMasterAttribution.getCollegeName() != null) {
                            attribution += postMasterAttribution.getCollegeName();
                        }
                        if (postMasterAttribution.getFamilyName() != null) {
                            attribution = attribution + "-" + postMasterAttribution.getFamilyName();
                        }
                        if (postMasterAttribution.getTeacherName() != null) {
                            attribution = attribution + "-" + postMasterAttribution.getTeacherName() + ";";
                        }
                    }
                }

                map.put("attribution", attribution);
                map.put("albumChildName", postMaster.getAlbumChildName());
                map.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMaster.getCreateTime()));
                map.put("userNickname", postMaster.getUserNickname());
                map.put("userMobile", postMaster.getUserMobile());
                map.put("userId", postMaster.getUserId());
                String postPurpose = "";
                PostOperationLog postOperationLog = postOperationLogService
                        .retrieveNewPostOperationLog(postMaster.getPostMasterId());
                if (postOperationLog != null) {

                    if (postOperationLog.getFirstReason() != null) {
                        postPurpose = postOperationLog.getFirstReason();
                    }
                    if (postOperationLog.getSecondReason() != null) {
                        postPurpose = postPurpose + ";" + postOperationLog.getSecondReason();
                    }
                    if (postOperationLog.getPostPurpose() != null) {
                        postPurpose = postPurpose + ";" + postOperationLog.getPostPurpose();
                    }
                }
                map.put("postPurpose", postPurpose);// 发帖动机
                map.put("sensitiveWord", postMaster.getSensitiveWord());// 包含的敏感词
                map.put("modifyTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMaster.getOperateTime()));
                map.put("operator", postMaster.getOperator());
                map.put("postState", postMaster.getPostState());
                resultList.add(map);
            }
            rstJson.put("resultMessage", resultList);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", e.getMessage());
            return rstJson.toString();
        }

        logger.debug("retrieveSlavePostList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }


    @Override
    public String getAllSensitiveDictionary(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getAllSensitiveDictionary(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

            if (reqSensitiveWordDTO == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }
            BaseSearchResultDTO<ResSensitiveWordDTO> result = managerPostService
                    .getAllSensitiveDictionary(reqSensitiveWordDTO);

            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
            rstJson.put("resultMessage", jsonObject);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "管理后台查询敏感词字典失败!" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("getAllSensitiveDictionary(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String getAllSensitiveWord(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getAllSensitiveWord(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        try {
            List<String> resList = managerPostService.getAllSensitiveWord();
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONArray.fromObject(resList, jsonConfig));
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取所有敏感词失败！" + e.getMessage());
        }
        logger.debug("getAllSensitiveWord	--end");

        return rstJson.toString();
    }

    @Override
    public String getAllSensitiveType(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getAllSensitiveType(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        try {

            List<ResSensitiveWordDTO> resList = managerPostService.getAllSensitiveType();
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONArray.fromObject(resList, jsonConfig));
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取所有敏感词分类失败！" + e.getMessage());
        }
        logger.debug("getAllSensitiveType	--end");

        return rstJson.toString();
    }

    @Override
    public String addSensitiveType(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("addSensitiveType(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        // try {
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

        if (reqSensitiveWordDTO == null || reqSensitiveWordDTO.getCreater() == null
                || reqSensitiveWordDTO.getSensitiveTypeName() == null) {
            throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
        }
        int rst = postSensitiveService.addSensitiveType(reqSensitiveWordDTO);
        rstJson.put("rsdesp", "Success");
        /*
         * } catch (Exception e) { rstJson.put("rs",
		 * DictConstants.API_RES_CODE_ERROR); rstJson.put("rsdesp", "新增敏感词分类失败！"
		 * + e.getMessage()); }
		 */
        logger.debug("addSensitiveType	--end");

        return rstJson.toString();
    }

    @Override
    public String updateSensitiveType(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updateSensitiveType(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

            if (reqSensitiveWordDTO == null || reqSensitiveWordDTO.getCreater() == null
                    || reqSensitiveWordDTO.getSensitiveTypeName() == null
                    || reqSensitiveWordDTO.getSensitiveTypeId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }
            int rst = postSensitiveService.updateSensitiveType(reqSensitiveWordDTO);
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "修改敏感词分类失败！" + e.getMessage());
        }
        logger.debug("updateSensitiveType	--end");

        return rstJson.toString();
    }

    @Override
    public String deleteSensitiveType(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("deleteSensitiveType(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

            if (reqSensitiveWordDTO == null || reqSensitiveWordDTO.getCreater() == null
                    || reqSensitiveWordDTO.getSensitiveTypeId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }
            int rst = postSensitiveService.deleteSensitiveType(reqSensitiveWordDTO);
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除敏感词分类失败！" + e.getMessage());
        }
        logger.debug("deleteSensitiveType	--end");

        return rstJson.toString();
    }

    @Override
    public String addSensitiveWord(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("addSensitiveWord(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

            if (reqSensitiveWordDTO == null || reqSensitiveWordDTO.getCreater() == null
                    || reqSensitiveWordDTO.getSensitiveName() == null
                    || reqSensitiveWordDTO.getSensitiveTypeId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }
            int rst = postSensitiveService.addSensitiveWord(reqSensitiveWordDTO);
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "新增敏感词分类失败！" + e.getMessage());
        }
        logger.debug("addSensitiveWord	--end");

        return rstJson.toString();
    }

    @Override
    public String updateSensitiveWord(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updateSensitiveWord(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

            if (reqSensitiveWordDTO == null || reqSensitiveWordDTO.getCreater() == null
                    || reqSensitiveWordDTO.getSensitiveName() == null || reqSensitiveWordDTO.getSensitiveId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }
            int rst = postSensitiveService.updateSensitiveWord(reqSensitiveWordDTO);
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "修改敏感词失败！" + e.getMessage());
        }
        logger.debug("updateSensitiveWord	--end");

        return rstJson.toString();
    }

    @Override
    public String deleteSensitiveWord(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("deleteSensitiveWord(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        ReqSensitiveWordDTO reqSensitiveWordDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            reqSensitiveWordDTO = JSON.parseObject(jsonStr, ReqSensitiveWordDTO.class);

            if (reqSensitiveWordDTO == null || reqSensitiveWordDTO.getCreater() == null
                    || reqSensitiveWordDTO.getSensitiveId() == null) {
                throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
            }
            int rst = postSensitiveService.deleteSensitiveWord(reqSensitiveWordDTO);
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除敏感词失败！" + e.getMessage());
        }
        logger.debug("deleteSensitiveWord	--end");

        return rstJson.toString();
    }

    @Override
    public String transferPostMaster(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("transferPostMaster(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

            if (postMasterDTO.getOperateFlag() == null || postMasterDTO.getOperateFlag() == 0) {
                // 单个操作
                if (postMasterDTO == null || postMasterDTO.getPostMasterId() == null
                        || postMasterDTO.getOldAlbumParentId() == null || postMasterDTO.getOldAlbumChildId() == null
                        || postMasterDTO.getNewAlbumParentId() == null || postMasterDTO.getNewAlbumChildId() == null
                        || postMasterDTO.getEmail() == null) {
                    throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
                }

                // 取消置顶
                postMasterDTO.setPostTop((byte) 0);
                // 设置迁移标签
                postMasterDTO.setPostTransfer(1);

                postMasterService.updateState(postMasterDTO);
                // 更改帖子分类
                postMasterService.updatePostType(postMasterDTO);
            } else {
                // 批量操作
                if (postMasterDTO == null || postMasterDTO.getTransferPostMasters() == null) {
                    throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
                }

                List<PostMaster> postMasters = postMasterDTO.getTransferPostMasters();
                if (!postMasters.isEmpty()) {
                    for (PostMaster postMaster : postMasters) {

                        postMasterDTO.setPostMasterId(postMaster.getPostMasterId());
                        postMasterDTO.setOldAlbumParentId(postMaster.getOldAlbumParentId());
                        postMasterDTO.setOldAlbumParentName(postMaster.getOldAlbumParentName());

                        postMasterDTO.setOldAlbumChildId(postMaster.getOldAlbumChildId());
                        postMasterDTO.setOldAlbumChildName(postMaster.getOldAlbumChildName());
                        // 取消置顶
                        postMasterDTO.setPostTop((byte) 0);
                        // 设置迁移标签
                        postMasterDTO.setPostTransfer(1);

                        postMasterService.updateState(postMasterDTO);
                        // 更改帖子分类
                        postMasterService.updatePostType(postMasterDTO);

                    }
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "迁移主贴失败!主贴id : " + postMasterDTO.getPostMasterId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("transferPostMaster(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String showNicknames(String jsonStr, String versionStr, String tokenStr) {

        logger.debug("showNicknames(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        try {
            String nickname = JSON.parseObject(jsonStr).getString("nickname");

            if (org.apache.commons.lang3.StringUtils.isBlank(nickname)) {

                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();

            }

            // 调用曙哥服务

            List<Student> showNicknames = checkIsVipClient.showNicknames(nickname);
            if (showNicknames != null && !showNicknames.isEmpty() && showNicknames.size() >= 10) {

                showNicknames = showNicknames.subList(0, 9);
            }

            rstJson.put("resultMessage", showNicknames);

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "根据关键词联想昵称失败！");
            return rstJson.toString();
        }

        logger.debug("showNicknames(String jsonStr={})	--start", jsonStr);

        return rstJson.toString();
    }

    @Override
    public String retrievePostReplyList(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("retrievePostReplyList(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostReplyDTO postReplyDTO = null;

        // 数据校验
        // try {
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        postReplyDTO = JSON.parseObject(jsonStr, ReqPostReplyDTO.class);

        if (postReplyDTO == null) {
            throw new BaseException(ExceptionCategory.Business_Query, "入参不能为空");
        }

        // 获取帖子列表
        if (postReplyDTO.getIsHide() != 0 && postReplyDTO.getDeleteFlag() == 1) {
            postReplyDTO.setQueryForManage(NumberUtils.BYTE_ONE);
        } else {
            postReplyDTO.setQueryForManage(NumberUtils.BYTE_ZERO);
        }

        // postSlaveDTO.setUserId(null);

        // 判断mobile，不为空
        if (StringUtils.isNotEmpty(postReplyDTO.getMobile())) {
            List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(postReplyDTO.getMobile());
            userIdList.add(-1);
            if (userIdList != null) {
                postReplyDTO.setUserIdList(userIdList);
            }

        }
        if (postReplyDTO.getPageNo() == null) {
            postReplyDTO.setPageNo(1);
        }
        if (postReplyDTO.getPageSize() == null) {
            postReplyDTO.setPageSize(10);
        }
        BaseSearchResultDTO<ResPostReplyDTO> result = managerPostService
                .retrieveBackGroundPostReplyListByCondition(postReplyDTO);

        JsonConfig jsonConfig = new JsonConfig();
        jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());

        JSONObject jsonObject = JSONObject.fromObject(result, jsonConfig);
        jsonObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postReplyDTO.getReqTime()));

        rstJson.put("resultMessage", jsonObject);

		/*
         * } catch (Exception e) { rstJson.put("rs",
		 * DictConstants.API_RES_CODE_ERROR); rstJson.put("rsdesp",
		 * "后端管理系统查询回复列表出错!" + e.getMessage()); return rstJson.toString(); }
		 */

        logger.debug("retrievePostReplyList(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostReplyHideState(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostReplyHideState(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostReplyDTO postReplyDTO = null;
        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReplyDTO = JSON.parseObject(jsonStr, ReqPostReplyDTO.class);
            /*
             * if (postReplyDTO.getReplyId() == null) { rstJson.put("rs",
			 * DictConstants.API_RES_CODE_ERROR); rstJson.put("rsdesp",
			 * "replyId不能为空"); return rstJson.toString(); }
			 */
            if (postReplyDTO.getOperateFlag() == null || postReplyDTO.getOperateFlag() == 0) {
                // 单条操作
                PostReply postReply = new PostReply();
                BeanUtils.copyProperties(postReplyDTO, postReply);
                postReply.setOperateTime(new Date());
                int rst = postReplyService.update(postReply);
                if (rst > 0) {
                    String type = postReplyOperationLogService.getReplyOperationType(postReplyDTO);
                    try {
                        ReqPostReplyOperationLogDTO operationLog = new ReqPostReplyOperationLogDTO(
                                postReplyDTO.getReplyId(), postReplyDTO.getEmail(), postReplyDTO.getPostPurpose(),
                                type);

                        postReplyOperationLogService.savePostReplyOperationLog(operationLog);

                        if (postReplyDTO.getIsHide() != null && postReplyDTO.getIsHide() != 0) {
                            // 屏蔽回复
                            postMasterService.reduceReplyCount(postReplyDTO.getPostMasterId());
                        } else {
                            postMasterService.addReplyCount(postReplyDTO.getPostMasterId());
                        }
                    } catch (Exception e) {
                        logger.debug("保存回复t_post_reply状态操作日志失败:" + e.getMessage());
                    }
                }
            } else {

                // 批量操作
                List<PostReply> postReplys = postReplyDTO.getPostReplys();

                if (postReplys != null && !postReplys.isEmpty()) {
                    for (PostReply postReply : postReplys) {

                        postReply.setIsHide(postReplyDTO.getIsHide());

                        postReply.setOperateTime(new Date());
                        int rst = postReplyService.update(postReply);
                        if (rst > 0) {
                            String type = postReplyOperationLogService.getReplyOperationType(postReplyDTO);
                            try {
                                ReqPostReplyOperationLogDTO operationLog = new ReqPostReplyOperationLogDTO(
                                        postReply.getReplyId(), postReplyDTO.getEmail(), postReplyDTO.getPostPurpose(),
                                        type);

                                postReplyOperationLogService.savePostReplyOperationLog(operationLog);

                                if (postReplyDTO.getIsHide() != null && postReplyDTO.getIsHide() != 0) {
                                    // 屏蔽回复
                                    postMasterService.reduceReplyCount(postReply.getPostMasterId());
                                } else {
                                    postMasterService.addReplyCount(postReply.getPostMasterId());
                                }
                            } catch (Exception e) {
                                logger.debug("保存回复t_post_reply状态操作日志失败:" + e.getMessage());
                            }
                        }

                    }
                }

            }

        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "更新回复屏蔽状态失败!replyId : " + postReplyDTO.getReplyId() + "," + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostReplyHideState(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String updatePostReplyDeleteFlag(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("updatePostReplyDeleteFlag(String jsonStr = {})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostReplyDTO postReplyDTO = null;
        ResPostReplyDTO resPostReplyDTO = null;

        try {
            if (StringUtils.isBlank(jsonStr)) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }

            postReplyDTO = JSON.parseObject(jsonStr, ReqPostReplyDTO.class);

			/*
             * if (postReplyDTO.getReplyId() == null) { rstJson.put("rs",
			 * DictConstants.API_RES_CODE_ERROR); rstJson.put("rsdesp",
			 * "replyId不能为空"); return rstJson.toString(); }
			 */
            if (postReplyDTO.getOperateFlag() == null || postReplyDTO.getOperateFlag() == 0) {

                // 单条操作
                resPostReplyDTO = postReplyService.retrieveByReplyId(postReplyDTO.getReplyId());

                PostReply postReply = new PostReply();
                BeanUtils.copyProperties(postReplyDTO, postReply);
                postReply.setOperateTime(new Date());
                int rst = postReplyService.update(postReply);

                if (rst == 0) {
                    rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                    rstJson.put("rsdesp", "删除回复失败！");
                    return rstJson.toString();
                } else {
                    if (resPostReplyDTO == null) {
                        return rstJson.toString();
                    }

                    ResPostMasterDTO resPostMasterDTO = postMasterService
                            .retrieveByPostMasterId(postReply.getPostMasterId());
                    if (resPostMasterDTO != null) {
                        Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                        Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                        if (albumParentId == null) {
                            albumParentId = 0;
                        }

                        if (albumChildId == null) {
                            albumChildId = 0;
                        }

                        albumCache.updateAlbumReplyTotalNum(albumParentId, 0, -1);

                        if (!albumChildId.equals(0)) {
                            albumCache.updateAlbumReplyTotalNum(0, albumChildId, -1);
                        }
                    }

                    String type = postReplyOperationLogService.getReplyOperationType(postReplyDTO);
                    try {
                        ReqPostReplyOperationLogDTO operationLog = new ReqPostReplyOperationLogDTO(
                                postReplyDTO.getReplyId(), postReplyDTO.getEmail(), postReplyDTO.getPostPurpose(),
                                type);

                        postReplyOperationLogService.savePostReplyOperationLog(operationLog);

                        if (postReplyDTO.getDeleteFlag() != null && postReplyDTO.getDeleteFlag() == 1) {
                            // 屏蔽回复
                            postMasterService.reduceReplyCount(postReplyDTO.getPostMasterId());
                        } else {
                            postMasterService.addReplyCount(postReplyDTO.getPostMasterId());
                        }
                    } catch (Exception e) {
                        logger.debug("保存回复状态操作日志失败:" + e.getMessage());
                    }
                }
            } else {

                // 批量操作
                List<PostReply> postReplys = postReplyDTO.getPostReplys();
                for (PostReply postReply : postReplys) {

                    resPostReplyDTO = postReplyService.retrieveByReplyId(postReply.getReplyId());
                    postReply.setDeleteFlag(postReplyDTO.getDeleteFlag());
                    postReply.setOperateTime(new Date());
                    int rst = postReplyService.update(postReply);

                    if (rst == 0) {
                        rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                        rstJson.put("rsdesp", "删除回复失败！");
                        return rstJson.toString();
                    } else {
                        if (resPostReplyDTO == null) {
                            return rstJson.toString();
                        }

                        ResPostMasterDTO resPostMasterDTO = postMasterService
                                .retrieveByPostMasterId(postReply.getPostMasterId());
                        if (resPostMasterDTO != null) {
                            Integer albumParentId = resPostMasterDTO.getAlbumParentId();
                            Integer albumChildId = resPostMasterDTO.getAlbumChildId();

                            if (albumParentId == null) {
                                albumParentId = 0;
                            }

                            if (albumChildId == null) {
                                albumChildId = 0;
                            }

                            albumCache.updateAlbumReplyTotalNum(albumParentId, 0, -1);

                            if (!albumChildId.equals(0)) {
                                albumCache.updateAlbumReplyTotalNum(0, albumChildId, -1);
                            }
                        }

                        String type = postReplyOperationLogService.getReplyOperationType(postReplyDTO);
                        try {
                            ReqPostReplyOperationLogDTO operationLog = new ReqPostReplyOperationLogDTO(
                                    postReply.getReplyId(), postReplyDTO.getEmail(), postReplyDTO.getPostPurpose(),
                                    type);

                            postReplyOperationLogService.savePostReplyOperationLog(operationLog);

                            if (postReplyDTO.getDeleteFlag() != null && postReplyDTO.getDeleteFlag() == 1) {
                                // 屏蔽回复
                                postMasterService.reduceReplyCount(postReply.getPostMasterId());
                            } else {
                                postMasterService.addReplyCount(postReply.getPostMasterId());
                            }
                        } catch (Exception e) {
                            logger.debug("保存回复状态操作日志失败:" + e.getMessage());
                        }
                    }

                }

            }
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "删除回复失败！" + e.getMessage());
            return rstJson.toString();
        }

        logger.debug("updatePostReplyDeleteFlag(String jsonStr = {})	--end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String getPostReplyOperationLog(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getPostReplyOperationLog(String jsonStr={})	--start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }

        try {
            Integer replyId = JSON.parseObject(jsonStr).getInteger("replyId");

            if (replyId == null) {
                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
                rstJson.put("rsdesp", "入参不能为空！");
                return rstJson.toString();
            }
            List<ResPostReplyOperationLogDTO> resList = postReplyOperationLogService.getPostReplyOperationLog(replyId);
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONArray.fromObject(resList, jsonConfig));
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "获取回复操作日志失败！" + e.getMessage());
        }
        logger.debug("getPostReplyOperationLog	--end");

        return rstJson.toString();
    }

    @Override
    public String getPostByTopic(String jsonStr, String versionStr, String tokenStr) {
        logger.debug("getPostByTopic(String jsonStr ={})		-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = null;

        // 数据校验
        // try {
        if (StringUtils.isBlank(jsonStr)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();
        }
//        try {
//            jsonStr = new String(jsonStr.getBytes("iso-8859-1"), "UTF-8");
//        } catch (UnsupportedEncodingException e) {
//            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
//            rstJson.put("rsdesp", e.getMessage());
//            return rstJson.toString();
//        }
        JSONObject jsonObject = JSONObject.fromObject(jsonStr);

        Integer topicId = null;
        if (jsonObject.containsKey("topicId")) {
            topicId = jsonObject.getInt("topicId");
        }

        String topicTitle = null;
        if (jsonObject.containsKey("topicName")) {
            topicTitle = jsonObject.getString("topicName");
        }
        if (topicId == null && StringUtils.isBlank(topicTitle)) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "话题ID或者话题名称至少有一个不能为空！");
            return rstJson.toString();
        }
        //获得话题信息
        Map<String, Object> topic = topicService.getTopicByIdOrTitle(topicId, topicTitle);
        if (topic != null) {
            if (!topic.containsKey("discussCount")) {
                topic.put("discussCount", 0);
            }
            topicId = MapUtils.getInteger(topic, "topicId", topicId);
            TTopic tTopic = JSON.parseObject(jsonStr, TTopic.class);
            tTopic.setTopicId(topicId);
            Map concern = topicService.isConcernTopic(tTopic);
            if (concern == null) {
                topic.put("isConcerned", 0);
            } else {
                topic.put("isConcerned", concern.get("isConcern"));
            }
            String postTimeStr = redisClient.get(RedisKeyPrefix.POST_TIME_TOPIC + topicId);
            if (postTimeStr != null && jsonObject.containsKey("userId")) {
                redisClient.set(RedisKeyPrefix.RT_USER_TOPIC + jsonObject.getString("userId") + "_" + topicId, postTimeStr);
            }
            rstJson.putAll(topic);
//            if (MapUtils.getBooleanValue(topic, "isShow") == false || MapUtils.getBooleanValue(topic, "deleteFlag") == true) {//话题被隐藏或者删除，不展示
//                rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
//                rstJson.put("rsdesp", "topic not found！Maybe hidden or deleted");
//                return rstJson.toString();
//            }
        } else {//话题不存在
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "topic does not exist！");
            return rstJson.toString();
        }
        //通过topicId或者topicTitle获得关联的主帖ID
        //List<Integer> postMasterIds = postTopicRelationService.getPostMasterIdsByTopicIdOrTopicTitle(topicId, topicTitle);
        //if (postMasterIds != null && postMasterIds.size() > 0) {
        Integer sortType = jsonObject.getInt("sortType");
        if (sortType == null) {
            sortType = 1;//默认按热度排序
        }
        //第一次请求时间
        String reqTime = null;
        if (jsonObject.containsKey("reqTime")) {
            reqTime = jsonObject.getString("reqTime");
        }

        postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);

        if (postMasterDTO == null) {
            throw new BaseException(ExceptionCategory.Business_Query, "子版id不能为空");
        }

        if (postMasterDTO.getMobile() != null) {

            Student student = checkIsVipClient.getUserByMobile(postMasterDTO.getMobile());

            if (student != null) {

                postMasterDTO.setUserId(student.getId());
            }

        }
        postMasterDTO.setIsHide(NumberUtils.BYTE_ZERO);
        postMasterDTO.setDeleteFlag(NumberUtils.BYTE_ZERO);
        // 获取帖子ID
        topicId = MapUtils.getInteger(topic, "topicId");
        Short topicType = MapUtils.getShort(topic, "topicType", (short) 2);
        BaseSearchResultDTO<ResPostMasterDTO> result = postMasterService
                .retrievePostListOnTopicByCondition(postMasterDTO, topicId, sortType, topicType);

        if (result != null) {
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());

            JSONObject jsonOutputObject = JSONObject.fromObject(result, jsonConfig);
            jsonOutputObject.put("reqTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(postMasterDTO.getReqTime()));
            rstJson.put("resultMessage", jsonOutputObject);

		/*
         * } catch (Exception e) { rstJson.put("rs",
		 * DictConstants.API_RES_CODE_ERROR); rstJson.put("rsdesp",
		 * "根据子版id查询出错!" + e.getMessage()); return rstJson.toString(); }
		 */
//        } else {
//            rstJson.put("resultMessage", "no related posts");
//        }
        } else {
            rstJson.put("resultMessage", "");
        }

        logger.debug("getPostByTopic(String jsonStr ={})		-- end", jsonStr);
        return rstJson.toString();
    }

    @Override
    public String test(String jsonStr, String versionStr, String tokenStr) {
        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);


        try {
            String mobile = JSON.parseObject(jsonStr).getString("mobile");
            List<Integer> userIdList = checkIsVipClient.getUserIdListByMobile(mobile);
            JsonConfig jsonConfig = new JsonConfig();
            jsonConfig.registerJsonValueProcessor(Date.class, new JsonDateValueProcessor());
            rstJson.put("resultMessage", JSONArray.fromObject(userIdList, jsonConfig));
            rstJson.put("rsdesp", "Success");
        } catch (Exception e) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "测试失败！" + e.getMessage());
        }
        return rstJson.toString();
    }

    @Override
    public String testSensitive(String jsonStr, String versionStr, String tokenStr) {

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        String string = "太多的伤感情怀也许只局限于饲养基地 荧幕中的情节，毛泽东主人公尝试着去用某种方式渐渐的很潇洒地释自杀指南怀那些自己经历的伤感。"
                + "然后法轮功 我们的扮演的角色就是跟随着主人公的喜红客联盟 怒哀乐而过于牵强的把自己的情感也附加于银幕情节中，然后感动就流泪，"
                + "难过就躺在某一个人的怀里尽情的阐述心扉或者手机卡复制器一个人一杯红酒一部电影在夜 深人尚宝宝静的晚上五星红，100%通过中国人民关上电话静静的发呆着。尚宝宝说";
        System.out.println("待检测语句字数：" + string.length());
        Set<String> sensitiveWord = postMasterService.getSensitiveWord(string);

        rstJson.put("resultMessage", JSONArray.fromObject(sensitiveWord));
        return rstJson.toString();
    }

    @Override
    public String testSendMail(String jsonStr, String versionStr, String tokenStr) {
        Mail mail = new Mail();

        mail.setSubject("韩中华测试");
        mail.setContent("我是胡大明");
        mail.setSendDate(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date()));
        Set<String> emails = new HashSet<String>();
        emails.add("hanzhonghua@sunlands.com");
        emails.add("hanzhonghua123@sunlands.com");
        emails.add("huxiaoming123@sunlands.com");
        emails.add("huxiaoming@sunlands.com");
        String[] tops = null;
        tops = emails.toArray(new String[]{});
        mail.setTops(tops);
        mailSendUtils.sendMail(mail);
        return null;
    }

    @Override
    public String topicPostByUsers(String jsonStr, String versionStr, String tokenStr) {
        logger.info("topicPostByUsers(String jsonStr={})	-- start", jsonStr);

        JSONObject rstJson = new JSONObject();
        rstJson.put("rs", DictConstants.API_RES_CODE_SUCCESS);

        ReqPostMasterDTO postMasterDTO = JSON.parseObject(jsonStr, ReqPostMasterDTO.class);
        if (postMasterDTO == null || StringUtils.isBlank(postMasterDTO.getAppVersion())
                || StringUtils.isBlank(postMasterDTO.getOsVersion())
                || StringUtils.isBlank(postMasterDTO.getChannelCode())
                || postMasterDTO.getUserId() == null || postMasterDTO.getPageSize() == null || postMasterDTO.getPageNo() == null
                || postMasterDTO.getTopicId() == null || StringUtil.isListEmpty(postMasterDTO.getUserIdList())) {
            rstJson.put("rs", DictConstants.API_RES_CODE_ERROR);
            rstJson.put("rsdesp", "入参不能为空！");
            return rstJson.toString();

        }
        BaseSearchResultDTO<ResPostMasterDTO> rst = postMasterService.topicPostByUsers(postMasterDTO);
        rstJson.put("resultMessage", rst);
        logger.info("topicPostByUsers(String rst={})	-- end", JSON.toJSONString(rst));
        return rstJson.toString();

    }
}
