package com.berry.oss.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.berry.oss.common.ResultCode;
import com.berry.oss.common.constant.CommonConstant;
import com.berry.oss.common.constant.Constants;
import com.berry.oss.common.exceptions.BaseException;
import com.berry.oss.common.exceptions.UploadException;
import com.berry.oss.common.exceptions.XmlResponseException;
import com.berry.oss.common.exceptions.xml.AccessDenied;
import com.berry.oss.common.exceptions.xml.NotFound;
import com.berry.oss.common.exceptions.xml.SignatureDoesNotMatch;
import com.berry.oss.common.utils.*;
import com.berry.oss.config.GlobalProperties;
import com.berry.oss.dao.entity.BucketInfo;
import com.berry.oss.dao.entity.ObjectInfo;
import com.berry.oss.dao.entity.RefererInfo;
import com.berry.oss.dao.service.IBucketInfoDaoService;
import com.berry.oss.dao.service.IObjectInfoDaoService;
import com.berry.oss.dao.service.IRefererInfoDaoService;
import com.berry.oss.module.dto.ObjectResource;
import com.berry.oss.module.vo.GenerateUrlWithSignedVo;
import com.berry.oss.module.vo.ObjectInfoVo;
import com.berry.oss.security.SecurityUtils;
import com.berry.oss.security.dto.UserInfoDTO;
import com.berry.oss.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Base64Utils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.berry.oss.common.constant.Constants.DEFAULT_FILE_PATH;

/**
 * Created with IntelliJ IDEA.
 *
 * @author Berry_Cooper.
 * @date 2019-06-04 22:33
 * fileName：ObjectServiceImpl
 * Use：
 */
@Service
public class ObjectServiceImpl implements IObjectService {

    private static final int UPLOAD_PER_SIZE_LIMIT = 200;
    private static final String BASE64_DATA_START_PATTERN = "data:image/[a-z];";

    private static final String CHART_SET = "UTF-8";

    private final IObjectInfoDaoService objectInfoDaoService;
    private final IObjectHashService objectHashService;
    private final IBucketService bucketService;
    private final IDataService dataService;
    private final IBucketInfoDaoService bucketInfoDaoService;
    private final GlobalProperties globalProperties;
    private final IAuthService authService;
    private final IRefererInfoDaoService refererInfoDaoService;

    @Value("${server.port}")
    private String port;

    ObjectServiceImpl(IObjectInfoDaoService objectInfoDaoService,
                      IObjectHashService objectHashService,
                      IBucketService bucketService,
                      IDataService dataService,
                      IBucketInfoDaoService bucketInfoDaoService,
                      GlobalProperties globalProperties,
                      IRefererInfoDaoService refererInfoDaoService,
                      IAuthService authService) {
        this.objectInfoDaoService = objectInfoDaoService;
        this.objectHashService = objectHashService;
        this.bucketService = bucketService;
        this.dataService = dataService;
        this.bucketInfoDaoService = bucketInfoDaoService;
        this.globalProperties = globalProperties;
        this.refererInfoDaoService = refererInfoDaoService;
        this.authService = authService;
    }

    @Override
    public List<ObjectInfo> list(String bucket, String path, String search) {
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();
        BucketInfo bucketInfo = bucketService.checkUserHaveBucket(bucket);
        QueryWrapper<ObjectInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", currentUser.getId());
        queryWrapper.eq("bucket_id", bucketInfo.getId());
        queryWrapper.eq("file_path", path);
        queryWrapper.orderByDesc("is_dir");
        if (StringUtils.isNotBlank(search)) {
            queryWrapper.likeRight("file_name", search);
        }
        return objectInfoDaoService.list(queryWrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public List<ObjectInfoVo> create(String bucket, MultipartFile[] files, String acl, String filePath) throws Exception {

        if (files.length > UPLOAD_PER_SIZE_LIMIT) {
            throw new UploadException("403", "最多同时上传数量为100");
        }

        // 验证acl 规范
        if (!CommonConstant.AclType.ALL_NAME.contains(acl)) {
            throw new UploadException("403", "不支持的ACL 可选值 [PRIVATE, PUBLIC_READ, PUBLIC_READ_WRITE]");
        }
        // 校验path 规范
        checkPath(filePath);

        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();

        // 检查bucket
        BucketInfo bucketInfo = getBucketInfo(bucket, currentUser);

        List<ObjectInfoVo> vos = new ArrayList<>();
        for (MultipartFile file : files) {
            // 计算文件 hash，获取文件大小
            String hash = SHA256.hash(file.getBytes());
            long fileSize = file.getSize();
            // 1. 获取请求头中，文件大小，文件hash
            if (fileSize > Integer.MAX_VALUE) {
                throw new UploadException("403", "文件大小不能超过2G");
            }

            // 校验通过
            String fileName = file.getOriginalFilename();
            if (StringUtils.isNotBlank(fileName)) {
                // 过滤替换文件名特殊字符
                fileName = StringUtils.filterUnsafeUrlCharts(fileName);
            }

            // 检查 该用户 同目录 同名 同bucket 下 文件是否已经存在（不检查文件内容 值判断路径和文件名）
            ObjectInfo objectInfo = getObjectInfo(filePath, currentUser.getId(), bucketInfo.getId(), fileName);
            boolean exist = objectInfo != null;

            ObjectInfoVo vo = new ObjectInfoVo();
            vo.setReplace(exist);

            if (!exist) {
                // 尝试快速上传
                String fileId = objectHashService.checkExist(hash, fileSize);
                if (StringUtils.isBlank(fileId)) {
                    vo.setUploadType(false);
                    // 快速上传失败，
                    // 调用存储数据服务，保存对象，返回24位对象id,
                    fileId = dataService.saveObject(filePath, file.getInputStream(), fileSize, hash, fileName, bucketInfo);
                }
                // 保存上传信息
                saveObjectInfo(bucketInfo.getId(), acl, hash, fileSize, fileName, filePath, fileId);
            } else {
                String oldHash = objectInfo.getHash();
                if (!oldHash.equals(hash)) {
                    // 文件内容变化
                    objectHashService.decreaseRefCountByHash(oldHash);
                    vo.setUploadType(false);
                    // 调用存储数据服务，保存对象，返回24位对象id,
                    String fileId = dataService.saveObject(filePath, file.getInputStream(), fileSize, hash, fileName, bucketInfo);
                    objectInfo.setFileId(fileId);
                    objectInfo.setHash(hash);
                    objectInfo.setSize(fileSize);
                    objectInfo.setFormattedSize(StringUtils.getFormattedSize(fileSize));
                    objectHashService.increaseRefCountByHash(hash, fileId, fileSize);
                }
                objectInfo.setUpdateTime(new Date());
                objectInfoDaoService.updateById(objectInfo);
            }
            buildResponse(bucket, filePath, fileName, acl, "", fileSize, vo);
            vos.add(vo);
        }
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ObjectInfoVo uploadByte(String bucket, String filePath, String fileName, byte[] data, String acl) throws Exception {
        // 检查文件名，验证acl 规范
        fileName = checkFilenameAndPath(filePath, fileName, acl);

        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();

        // 检查bucket
        BucketInfo bucketInfo = getBucketInfo(bucket, currentUser);

        // 计算数据hash
        String hash = SHA256.hash(data);
        // 大小
        long size = data.length;

        // 返回对象
        ObjectInfoVo vo = new ObjectInfoVo();

        // 保存或更新改对象信息
        saveOrUpdateObject(filePath, data, acl, currentUser.getId(), bucketInfo, hash, size, vo, fileName);

        buildResponse(bucket, filePath, fileName, acl, "", size, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ObjectInfoVo uploadByBase64Str(String bucket, String filePath, String fileName, String data, String acl) throws Exception {
        fileName = checkFilenameAndPath(filePath, fileName, acl);

        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();

        // 检查bucket
        BucketInfo bucketInfo = getBucketInfo(bucket, currentUser);

        // 检查数据格式
        String[] dataArr = data.split("base64,");
        if (dataArr.length != 2 || dataArr[0].matches(BASE64_DATA_START_PATTERN)) {
            throw new UploadException("403", "非法base64数据");
        }

        String fileType = getFileType(dataArr[0]);
        // 计算数据hash
        byte[] byteData = Base64Utils.decodeFromString(dataArr[1]);
        String hash = SHA256.hash(byteData);
        long size = dataArr[1].length();

        // 返回对象
        ObjectInfoVo vo = new ObjectInfoVo();

        // 保存或更新改对象信息
        saveOrUpdateObject(filePath, byteData, acl, currentUser.getId(), bucketInfo, hash, size, vo, fileName + fileType);

        buildResponse(bucket, filePath, fileName, acl, fileType, size, vo);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createFolder(String bucket, String folder) {
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();

        // 检查bucket
        BucketInfo bucketInfo = bucketService.checkUserHaveBucket(bucket);

        List<ObjectInfo> infos = getFolderInfoList(currentUser.getId(), bucketInfo.getId(), folder);
        if (infos.size() > 0) {
            objectInfoDaoService.insertIgnoreBatch(infos);
        }
    }

    @Override
    public void getObject(String bucket, String expiresTime, String ossAccessKeyId, String signature, Boolean download, HttpServletResponse response, HttpServletRequest servletRequest, WebRequest request) throws IOException {

        String objectPath = extractPathFromPattern(servletRequest);

        // 检查bucket
        BucketInfo bucketInfo = bucketInfoDaoService.getOne(new QueryWrapper<BucketInfo>().eq("name", bucket));
        if (null == bucketInfo) {
            throw new XmlResponseException(new AccessDenied("bucket does not exist"));
        }

        boolean skipCheckAuth = false;
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();
        boolean anonymous = currentUser == null || currentUser.getId() == null;

        if (!anonymous) {
            // 用户请求头中带有密钥口令，无需url验证
            // 1. 检查当前用户 是否拥有对 所请求bucket的访问权限，通过后 可获取对该bucket的完全权限,跳过 url 校验
            skipCheckAuth = authService.checkUserHaveAccessToBucketObject(currentUser, bucket, "/" + objectPath);
        }

        String fileName = objectPath;
        String filePath = DEFAULT_FILE_PATH;
        if (objectPath.contains(DEFAULT_FILE_PATH)) {
            fileName = objectPath.substring(objectPath.lastIndexOf("/") + 1);
            filePath = "/" + objectPath.substring(0, objectPath.lastIndexOf("/"));
        }
        ObjectInfo objectInfo = objectInfoDaoService.getOne(new QueryWrapper<ObjectInfo>()
                .eq("file_name", fileName)
                .eq("file_path", filePath)
                .eq("bucket_id", bucketInfo.getId())
        );
        if (objectInfo == null) {
            // 资源不存在
            throw new XmlResponseException(new NotFound());
        }
        if (!skipCheckAuth && anonymous && objectInfo.getAcl().startsWith("PUBLIC")) {
            // 匿名访问 公开资源，检查 referer
            checkReferer(request, bucketInfo);
        }

        if (!objectInfo.getAcl().startsWith("PUBLIC") && !skipCheckAuth) {
            if (StringUtils.isAnyBlank(expiresTime, ossAccessKeyId, signature)) {
                throw new XmlResponseException(new AccessDenied("illegal url"));
            }

            // 非公开资源，需要验证身份及签名
            String url = "Expires=" + expiresTime + "&OSSAccessKeyId=" + URLEncoder.encode(ossAccessKeyId, CHART_SET);

            // 1. 签名验证
            String sign = new String(Base64.getEncoder().encode(MD5.md5Encode(url).getBytes()));
            if (!signature.equals(sign)) {
                throw new XmlResponseException(new SignatureDoesNotMatch(ossAccessKeyId, signature, servletRequest.getMethod() + " " + expiresTime + " " + objectPath));
            }

            // 2. 过期验证
            if (StringUtils.isNumeric(expiresTime)) {
                // 时间戳字符串转时间,expiresTime 是秒单位
                Date date = new Date(Long.parseLong(expiresTime) * 1000);
                if (date.before(new Date())) {
                    throw new XmlResponseException(new AccessDenied("Request has expired."));
                }
            }

            // 3. 身份验证,这里采用私钥加密，公钥解密，而没有采用 私钥签名，后续可能对账户信息进行控制，故而采用私钥加密 用户id，备解密时需要
            try {
                String userIdEncodePart = ossAccessKeyId.substring(4);
                RSAUtil.decryptByPublicKey(userIdEncodePart);
            } catch (Exception e) {
                throw new XmlResponseException(new AccessDenied("identity check fail."));
            }
        }

        handlerResponse(bucket, objectPath, response, request, objectInfo, download);
    }

    private void checkReferer(WebRequest request, BucketInfo bucketInfo) {
        // 匿名访问，检查 referer
        String headReferer = request.getHeader("Referer");
        RefererInfo refererInfo = refererInfoDaoService.getOne(new QueryWrapper<RefererInfo>().eq("bucket_id", bucketInfo.getId()));
        if (refererInfo != null) {
            Boolean allowEmpty = refererInfo.getAllowEmpty();
            String whiteList = refererInfo.getWhiteList();
            String blackList = refererInfo.getBlackList();
            // 两者同时设置方可生效
            if (allowEmpty != null && StringUtils.isNotBlank(whiteList)) {
                // 1.不允许 空 referer，deny
                if (StringUtils.isBlank(headReferer) && !allowEmpty) {
                    throw new XmlResponseException(new AccessDenied("referer deny"));
                }
                if (StringUtils.isNotBlank(headReferer)) {
                    // 2. 黑名单中，deny
                    String[] blackArr = blackList.split(",");
                    for (String black : blackArr) {
                        if (headReferer.matches(black)) {
                            throw new XmlResponseException(new AccessDenied("referer deny"));
                        }
                    }
                    // 3. 白名单，pass
                    String[] whiteArr = whiteList.split(",");
                    boolean match = false;
                    for (String white : whiteArr) {
                        if (headReferer.matches(white)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
                        throw new XmlResponseException(new AccessDenied("referer deny"));
                    }
                }

            }
        }
    }

    @Override
    public Map<String, Object> getObjectHead(String bucket, String path, String objectName) {
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();
        BucketInfo bucketInfo = bucketService.checkUserHaveBucket(bucket);
        String eTag = DigestUtils.md5DigestAsHex(objectName.getBytes());
        QueryWrapper<ObjectInfo> queryWrapper = new QueryWrapper<ObjectInfo>()
                .eq("bucket_id", bucketInfo.getId())
                .eq("user_id", currentUser.getId())
                .eq("file_path", path)
                .eq("file_name", objectName);
        ObjectInfo objectInfo = objectInfoDaoService.getOne(queryWrapper);
        if (objectInfo == null) {
            throw new BaseException(ResultCode.DATA_NOT_EXIST);
        }
        Map<String, Object> response = new HashMap<>(4);
        response.put("contentLength", objectInfo.getSize());
        response.put("contentType", objectInfo.getCategory());
        response.put("eTag", eTag);
        response.put("lastModified", objectInfo.getUpdateTime());
        return response;
    }

    @Override
    public GenerateUrlWithSignedVo generateUrlWithSigned(String bucket, String objectPath, Integer timeout) throws Exception {
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();

        if (!bucketService.checkUserHaveBucket(currentUser.getId(), bucket)) {
            throw new BaseException(ResultCode.DATA_NOT_EXIST);
        }

        // 将用户id 计算如签名，作为临时 ossAccessKeyId,解密时获取用户id
        String ossAccessKeyId = "TMP." + RSAUtil.encryptByPrivateKey(currentUser.getId().toString());

        if (!objectPath.startsWith(DEFAULT_FILE_PATH)) {
            objectPath = DEFAULT_FILE_PATH + objectPath;
        }
        String ip = globalProperties.getServerIp();
        String url = "http://" + ip + ":" + port + "/ajax/bucket/file/" + bucket + objectPath;

        long expires = (System.currentTimeMillis() + timeout * 1000) / 1000;
        String tempAccessKeyId = URLEncoder.encode(ossAccessKeyId, CHART_SET);

        Map<String, Object> paramsMap = new HashMap<>(3);
        paramsMap.put("Expires", expires);
        paramsMap.put("OSSAccessKeyId", tempAccessKeyId);
        String urlExpiresAccessKeyId = StringUtils.sortMap(paramsMap);

        // 对 参数部分 进行md5签名计算,并 base64编码
        String sign = new String(Base64.getEncoder().encode(MD5.md5Encode(urlExpiresAccessKeyId).getBytes()));

        // 拼接签名到url
        String signature = urlExpiresAccessKeyId + "&Signature=" + URLEncoder.encode(sign, CHART_SET);

        // 没有域名地址表，这里手动配置ip和端口
        return new GenerateUrlWithSignedVo()
                .setUrl(url)
                .setSignature(signature);
    }

    @Override
    public List<String> generateDownloadUrl(String bucket, List<String> objectPath) throws Exception {
        List<String> url = new ArrayList<>();
        for (String object : objectPath) {
            GenerateUrlWithSignedVo generateUrlWithSignedVo = generateUrlWithSigned(bucket, object, 60);
            url.add(generateUrlWithSignedVo.getUrl() + "?" + generateUrlWithSignedVo.getSignature() + "&Download=true");
        }
        return url;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(String bucket, String objectIds) {
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();

        // 检查bucket
        BucketInfo bucketInfo = bucketService.checkUserHaveBucket(bucket);

        String[] objectIdArray = objectIds.split(",");

        List<ObjectInfo> objectInfos = new ArrayList<>(objectInfoDaoService.listByIds(Arrays.asList(objectIdArray)));

        if (objectInfos.size() > 0) {
            List<ObjectInfo> files = objectInfos.stream().filter(info -> !info.getIsDir()).collect(Collectors.toList());

            if (files.size() > 0) {
                objectInfoDaoService.removeByIds(files.stream().map(ObjectInfo::getId).collect(Collectors.toList()));
                files.forEach(file -> {
                    // 对象hash引用 计数 -1
                    objectHashService.decreaseRefCountByHash(file.getHash());
                });
            }

            List<ObjectInfo> dirs = objectInfos.stream().filter(ObjectInfo::getIsDir).collect(Collectors.toList());

            if (dirs.size() > 0) {
                // 删除文件夹本身
                objectInfoDaoService.removeByIds(dirs.stream().map(ObjectInfo::getId).collect(Collectors.toList()));
                // 删除文件夹的子目录
                dirs.forEach(dir -> {
                    // 如果是文件夹，则删除该文件夹下所有的子项
                    objectInfoDaoService.remove(new QueryWrapper<ObjectInfo>()
                            .eq("bucket_id", bucketInfo.getId())
                            .eq("file_path", dir.getFilePath() + dir.getFileName())
                            .eq("user_id", currentUser.getId()));
                });
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateObjectAcl(String bucket, String objectPath, String objectName, String acl) {
        // 检查bucket
        BucketInfo bucketInfo = bucketService.checkUserHaveBucket(bucket);

        // 检查该对象是否存在
        ObjectInfo objectInfo = objectInfoDaoService.getOne(new QueryWrapper<ObjectInfo>()
                .eq("file_path", objectPath)
                .eq("file_name", objectName)
                .eq("bucket_id", bucketInfo.getId())
        );
        if (objectInfo == null) {
            throw new BaseException(ResultCode.DATA_NOT_EXIST);
        }
        objectInfo.setAcl(acl);
        return objectInfoDaoService.updateById(objectInfo);
    }

    @Async("taskExecutor")
    public void saveObjectInfo(String bucketId, String acl, String hash, Long contentLength, String fileName, String filePath, String fileId) {
        UserInfoDTO currentUser = SecurityUtils.getCurrentUser();
        List<ObjectInfo> newObject = new ArrayList<>();
        // 添加 该账号 该文件记录
        ObjectInfo objectInfo = new ObjectInfo()
                .setId(ObjectId.get())
                .setBucketId(bucketId)
                .setCategory(StringUtils.getExtName(fileName))
                .setFileId(fileId)
                .setSize(contentLength)
                .setFileName(fileName)
                .setFilePath(filePath)
                .setIsDir(false)
                .setAcl(acl)
                .setHash(hash)
                .setUserId(currentUser.getId())
                .setFormattedSize(StringUtils.getFormattedSize(contentLength));
        newObject.add(objectInfo);
        // 检查文件路径，非 / 则需要创建目录
        if (!DEFAULT_FILE_PATH.equals(filePath)) {
            List<ObjectInfo> infos = getFolderInfoList(currentUser.getId(), bucketId, filePath);
            if (infos.size() > 0) {
                // 添加目录信息
                newObject.addAll(infos);
            }
        }
        // 文件信息 和 目录信息同时 insert
        objectInfoDaoService.insertIgnoreBatch(newObject);

        // 引用+1
        objectHashService.increaseRefCountByHash(hash, fileId, contentLength);
    }

    private void saveOrUpdateObject(String filePath, byte[] data, String acl, Integer userId, BucketInfo bucketInfo, String hash, long size, ObjectInfoVo vo, String fullFileName) throws IOException {
        // 检查 该用户 同目录 同名 同bucket 下 文件是否已经存在
        ObjectInfo objectInfo = getObjectInfo(filePath, userId, bucketInfo.getId(), fullFileName);
        boolean exist = objectInfo != null;

        vo.setReplace(exist);

        if (!exist) {
            // 尝试快速上传
            String fileId = objectHashService.checkExist(hash, size);
            if (StringUtils.isBlank(fileId)) {
                // 快速上传失败，
                vo.setUploadType(false);
                // 调用存储数据服务，保存对象，返回24位对象id,
                fileId = dataService.saveObject(filePath, data, size, hash, fullFileName, bucketInfo);
            }
            // 保存上传信息
            saveObjectInfo(bucketInfo.getId(), acl, hash, size, fullFileName, filePath, fileId);
        } else {
            objectInfo.setUpdateTime(new Date());
            objectInfoDaoService.updateById(objectInfo);
        }
    }

    private String checkFilenameAndPath(String filePath, String fileName, String acl) {
        // 验证acl 规范
        if (!CommonConstant.AclType.ALL_NAME.contains(acl)) {
            throw new UploadException("403", "不支持的ACL 可选值 [PRIVATE, PUBLIC_READ, PUBLIC_READ_WRITE]");
        }

        // 过滤替换文件名特殊字符
        fileName = StringUtils.filterUnsafeUrlCharts(fileName);

        // 校验path 规范
        checkPath(filePath);
        return fileName;
    }

    private void buildResponse(String bucket, String filePath, String fileName, String acl, String fileType, long size, ObjectInfoVo vo) {
        vo.setAcl(acl);
        vo.setFileName(fileName);
        vo.setFilePath(filePath);
        vo.setSize(size);
        vo.setFormattedSize(StringUtils.getFormattedSize(size));
        String url = getPublicObjectUrl(bucket, filePath, fileName);
        vo.setUrl(url + fileType);
    }

    private BucketInfo getBucketInfo(String bucket, UserInfoDTO currentUser) {
        BucketInfo bucketInfo = bucketInfoDaoService.getOne(new QueryWrapper<BucketInfo>()
                .eq("name", bucket)
                .eq("user_id", currentUser.getId())
        );
        if (bucketInfo == null) {
            throw new UploadException("404", "bucket not exist");
        }
        return bucketInfo;
    }

    private static String getFileType(String dataPrefix) {
        return "." + dataPrefix.substring(dataPrefix.lastIndexOf("/") + 1, dataPrefix.length() - 1);
    }

    private static void checkPath(String filePath) {
        // 校验path 规范
        if (!DEFAULT_FILE_PATH.equals(filePath)) {
            boolean matches = filePath.substring(1).matches(Constants.FILE_PATH_PATTERN);
            if (!filePath.startsWith(DEFAULT_FILE_PATH) || !matches) {
                throw new UploadException("403", "当前上传文件目录不正确！filePath:" + filePath);
            }
        }
    }

    /**
     * 检查文件是否存在 返回是否替换
     *
     * @return true or false
     */
    private ObjectInfo getObjectInfo(String filePath, Integer userId, String bucketId, String fileName) {
        // 检查该 bucket 及 path 下 同名文件是否存在
        QueryWrapper<ObjectInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        queryWrapper.eq("bucket_id", bucketId);
        queryWrapper.eq("file_path", filePath);
        queryWrapper.eq("file_name", fileName);
        return objectInfoDaoService.getOne(queryWrapper);
    }

    /**
     * 组装需要创建目录对象
     *
     * @param userId   用户id
     * @param bucketId bucketId
     * @param filePath 文件夹全路径
     */
    private List<ObjectInfo> getFolderInfoList(Integer userId, String bucketId, String filePath) {
        // 1. 检查路径是否存在
        String folder = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        // 不存在则 进行创建
        String[] objectArr = folder.split("/");
        List<ObjectInfo> list = new ArrayList<>();
        ObjectInfo objectInfo;
        StringBuilder path = new StringBuilder("/");
        for (String dirName : objectArr) {
            if (StringUtils.isNotBlank(dirName)) {
                objectInfo = new ObjectInfo();
                objectInfo.setId(ObjectId.get());
                objectInfo.setIsDir(true);
                objectInfo.setFileName(dirName);
                objectInfo.setFilePath(path.toString());
                objectInfo.setUserId(userId);
                objectInfo.setBucketId(bucketId);
                if ("/".equals(path.toString())) {
                    path.append(dirName);
                } else {
                    path.append("/").append(dirName);
                }
                list.add(objectInfo);
            }
        }
        return list;
    }

    /**
     * 把指定URL后的字符串全部截断当成参数
     *
     * @param request request
     * @return 参数字符串
     */
    private static String extractPathFromPattern(final HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return new AntPathMatcher().extractPathWithinPattern(bestMatchPattern, path);
    }

    /**
     * 处理对象读取响应
     *
     * @param bucket
     * @param objectPath 对象全路径 如：/test.jpg
     * @param response   响应
     * @param request    请求
     * @param objectInfo 对象信息
     * @param download   是否是下载
     * @throws IOException IO 异常
     */
    private void handlerResponse(String bucket, String objectPath, HttpServletResponse response, WebRequest request, ObjectInfo objectInfo, Boolean download) throws IOException {
        if (download != null && download) {
            ObjectResource object = dataService.getObject(bucket, objectInfo.getFileId());
            if (object == null || object.getInputStream() == null) {
                throw new XmlResponseException(new NotFound());
            }
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            StreamUtils.copy(object.getInputStream(), response.getOutputStream());
            response.flushBuffer();
            return;
        }

//        long lastModified = objectInfo.getUpdateTime().toEpochSecond(OffsetDateTime.now().getOffset()) * 1000;
        long lastModified = objectInfo.getUpdateTime().getTime();
        String eTag = "\"" + DigestUtils.md5DigestAsHex(objectPath.getBytes()) + "\"";
        if (request.checkNotModified(eTag, lastModified)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        } else {
            ObjectResource object = dataService.getObject(bucket, objectInfo.getFileId());
            if (object == null) {
                throw new XmlResponseException(new NotFound());
            }
            String contentType = StringUtils.getContentType(object.getFileName());
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.ETAG, eTag);
            ZonedDateTime expiresDate = ZonedDateTime.now().with(LocalTime.MAX);
            String expires = expiresDate.format(DateTimeFormatter.RFC_1123_DATE_TIME);
            response.setHeader(HttpHeaders.EXPIRES, expires);
            StreamUtils.copy(object.getInputStream(), response.getOutputStream());
            response.flushBuffer();
        }
    }

    private String getPublicObjectUrl(String bucket, String filePath, String fileName) {
        String ip = globalProperties.getServerIp();
        String objectPath = filePath + "/" + fileName;
        if (filePath.equals(DEFAULT_FILE_PATH)) {
            objectPath = "/" + fileName;
        }
        return "http://" + ip + ":" + port + "/ajax/bucket/file/" + bucket + objectPath;
    }
}
