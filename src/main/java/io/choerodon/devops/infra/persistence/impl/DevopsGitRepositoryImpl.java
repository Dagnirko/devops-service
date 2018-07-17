package io.choerodon.devops.infra.persistence.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import io.choerodon.core.convertor.ConvertHelper;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.api.dto.AuthorDTO;
import io.choerodon.devops.api.dto.CommitDTO;
import io.choerodon.devops.api.dto.MergeRequestDTO;
import io.choerodon.devops.api.dto.TagDTO;
import io.choerodon.devops.domain.application.entity.*;
import io.choerodon.devops.domain.application.entity.gitlab.CommitE;
import io.choerodon.devops.domain.application.entity.iam.UserE;
import io.choerodon.devops.domain.application.repository.*;
import io.choerodon.devops.domain.application.valueobject.Organization;
import io.choerodon.devops.infra.common.util.GitUserNameUtil;
import io.choerodon.devops.infra.common.util.TypeUtil;
import io.choerodon.devops.infra.dataobject.ApplicationDO;
import io.choerodon.devops.infra.dataobject.DevopsBranchDO;
import io.choerodon.devops.infra.dataobject.gitlab.BranchDO;
import io.choerodon.devops.infra.dataobject.gitlab.CommitDO;
import io.choerodon.devops.infra.dataobject.gitlab.TagDO;
import io.choerodon.devops.infra.dataobject.gitlab.TagNodeDO;
import io.choerodon.devops.infra.feign.GitlabServiceClient;
import io.choerodon.devops.infra.mapper.ApplicationMapper;
import io.choerodon.devops.infra.mapper.DevopsBranchMapper;
import io.choerodon.devops.infra.mapper.DevopsMergeRequestMapper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import io.choerodon.mybatis.util.StringUtil;

/**
 * Creator: Runge
 * Date: 2018/7/2
 * Time: 14:02
 * Description:
 */
@Component
public class DevopsGitRepositoryImpl implements DevopsGitRepository {

    @Value("${services.gitlab.url}")
    private String gitlabUrl;

    @Autowired
    private GitlabServiceClient gitlabServiceClient;
    @Autowired
    private ApplicationMapper applicationMapper;
    @Autowired
    private UserAttrRepository userAttrRepository;
    @Autowired
    private DevopsBranchMapper devopsBranchMapper;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private IamRepository iamRepository;
    @Autowired
    private DevopsGitRepository devopsGitRepository;
    @Autowired
    private DevopsMergeRequestMapper devopsMergeRequestMapper;
    @Autowired
    private DevopsMergeRequestRepository devopsMergeRequestRepository;

    @Override
    public void createTag(Integer gitLabProjectId, String tag, String ref, Integer userId) {
        gitlabServiceClient.createTag(gitLabProjectId, tag, ref, userId);
    }

    @Override
    public void deleteTag(Integer gitLabProjectId, String tag, Integer userId) {
        gitlabServiceClient.deleteTag(gitLabProjectId, tag, userId);
    }

    @Override
    public Integer getGitLabId(Long applicationId) {
        ApplicationDO applicationDO = applicationMapper.selectByPrimaryKey(applicationId);
        if (applicationDO != null) {
            return applicationDO.getGitlabProjectId();
        } else {
            throw new CommonException("error.application.select");
        }
    }

    @Override
    public Integer getGitlabUserId() {
        UserAttrE userAttrE = userAttrRepository.queryById(TypeUtil.objToLong(GitUserNameUtil.getUserId()));
        return TypeUtil.objToInteger(userAttrE.getGitlabUserId());
    }

    @Override
    public Long getUserIdByGitlabUserId(Long gitLabUserId) {
        try {
            return userAttrRepository.queryUserIdByGitlabUserId(gitLabUserId);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getGitlabUrl(Long projectId, Long appId) {
        ApplicationE applicationE = applicationRepository.query(appId);
        if (applicationE.getGitlabProjectE() != null && applicationE.getGitlabProjectE().getId() != null) {
            ProjectE projectE = iamRepository.queryIamProject(projectId);
            Organization organization = iamRepository.queryOrganizationById(projectE.getOrganization().getId());
            String urlSlash = gitlabUrl.endsWith("/") ? "" : "/";
            return gitlabUrl + urlSlash
                    + organization.getCode() + "-" + projectE.getCode() + "/"
                    + applicationE.getCode();
        }
        return "";
    }

    @Override
    public BranchDO createBranch(Integer projectId, String branchName, String baseBranch, Integer userId) {
        ResponseEntity<BranchDO> responseEntity =
                gitlabServiceClient.createBranch(projectId, branchName, baseBranch, userId);
        if ("create branch message:Branch already exists".equals(responseEntity.getBody().getName())) {
            throw new CommonException("error.branch.exist");
        }
        return responseEntity.getBody();
    }

    @Override
    public List<BranchDO> listGitLabBranches(Integer projectId, String path, Integer userId) {
        ResponseEntity<List<BranchDO>> responseEntity = gitlabServiceClient.listBranches(projectId, userId);
        if (responseEntity.getStatusCode() != HttpStatus.OK) {
            throw new CommonException("error.branch.get");
        }
        List<BranchDO> branches = responseEntity.getBody();
        branches.forEach(t -> t.getCommit().setUrl(
                String.format("%s/commit/%s?view=parallel", path, t.getCommit().getId())));
        return branches;
    }

    @Override
    public List<DevopsBranchDO> listBranches(Long appId) {
        DevopsBranchDO branchDO = new DevopsBranchDO();
        branchDO.setAppId(appId);
        branchDO.setDeleted(false);
        return devopsBranchMapper.select(branchDO);
    }

    @Override
    public void deleteBranch(Integer projectId, String branchName, Integer userId) {
        gitlabServiceClient.deleteBranch(projectId, branchName, userId);
    }

    @Override
    public void deleteDevopsBranch(Long appId, String branchName) {
        DevopsBranchDO devopsBranchDO = devopsBranchMapper
                .queryByAppAndBranchName(appId, branchName);
        devopsBranchDO.setDeleted(true);
        devopsBranchMapper.updateByPrimaryKeySelective(devopsBranchDO);
    }

    @Override
    public Page<TagDTO> getTags(Long appId, String path, Integer page, Integer size, Integer userId) {
        Integer projectId = getGitLabId(appId);
        List<TagDO> tagTotalList = getGitLabTags(projectId, userId);
        Integer totalSize = tagTotalList.size();
        int totalPageSizes = totalSize / size + (totalSize % size == 0 ? 0 : 1);
        if (page > totalPageSizes - 1 && page > 0) {
            page = totalPageSizes - 1;
        }

        List<TagDTO> tagList = tagTotalList.stream()
                .sorted(this::sortTag)
                .skip(page.longValue() * size).limit(size)
                .map(TagDTO::new)
                .parallel()
                .peek(t -> {
                    UserE commitUserE = iamRepository.queryByLoginName(t.getCommit()
                            .getAuthorName().equals("root") ? "admin" : t.getCommit().getAuthorName());
                    t.setCommitUserImage(commitUserE.getImageUrl());
                    t.getCommit().setUrl(String.format("%s/commit/%s?view=parallel", path, t.getCommit().getId()));
                })
                .collect(Collectors.toCollection(ArrayList::new));
        Page<TagDTO> tagsPage = new Page<>();
        tagsPage.setSize(size);
        tagsPage.setTotalElements(totalSize);
        tagsPage.setTotalPages(totalPageSizes);
        tagsPage.setContent(tagList);
        tagsPage.setNumber(page);
        tagsPage.setNumberOfElements(tagList.size());
        return tagsPage;
    }

    @Override
    public List<TagDO> getTagList(Long appId, Integer userId) {
        Integer projectId = getGitLabId(appId);
        return getGitLabTags(projectId, userId);
    }

    @Override
    public List<TagDO> getGitLabTags(Integer projectId, Integer userId) {
        ResponseEntity<List<TagDO>> tagResponseEntity = gitlabServiceClient.getTags(projectId, userId);
        if (tagResponseEntity.getStatusCode() != HttpStatus.OK) {
            throw new CommonException("error.tags.get");
        }
        return tagResponseEntity.getBody();
    }

    @Override
    public DevopsBranchE queryByAppAndBranchName(Long appId, String branchName) {
        return ConvertHelper.convert(devopsBranchMapper
                .queryByAppAndBranchName(appId, branchName), DevopsBranchE.class);
    }

    @Override
    public void updateBranchIssue(Long appId, DevopsBranchE devopsBranchE) {
        DevopsBranchDO devopsBranchDO = devopsBranchMapper
                .queryByAppAndBranchName(appId, devopsBranchE.getBranchName());
        devopsBranchDO.setIssueId(devopsBranchE.getIssueId());
        devopsBranchMapper.updateByPrimaryKey(devopsBranchDO);
    }

    @Override
    public void updateBranchLastCommit(DevopsBranchE devopsBranchE) {
        DevopsBranchDO branchDO = devopsBranchMapper
                .queryByAppAndBranchName(devopsBranchE.getApplicationE().getId(), devopsBranchE.getBranchName());
        branchDO.setLastCommit(devopsBranchE.getLastCommit());
        branchDO.setLastCommitDate(devopsBranchE.getLastCommitDate());
        branchDO.setLastCommitMsg(devopsBranchE.getLastCommitMsg());
        branchDO.setLastCommitUser(devopsBranchE.getLastCommitUser());
        devopsBranchMapper.updateByPrimaryKey(branchDO);

    }

    @Override
    public void createDevopsBranch(DevopsBranchE devopsBranchE) {
        devopsBranchE.setDeleted(false);
        devopsBranchMapper.insert(ConvertHelper.convert(devopsBranchE, DevopsBranchDO.class));
    }

    @Override
    public Map<String, Object> getMergeRequestList(Long projectId, Integer gitLabProjectId,
                                                   String state,
                                                   PageRequest pageRequest) {
        List<DevopsMergeRequestE> allMergeRequest = devopsMergeRequestRepository
                .getByGitlabProjectId(gitLabProjectId);
        final int[] count = {0, 0, 0};
        if (allMergeRequest != null && !allMergeRequest.isEmpty()) {
            allMergeRequest.forEach(devopsMergeRequestE -> {
                if ("merged".equals(devopsMergeRequestE.getState())) {
                    count[0]++;
                } else if ("opened".equals(devopsMergeRequestE.getState())) {
                    count[1]++;
                } else if ("closed".equals(devopsMergeRequestE.getState())) {
                    count[2]++;
                }
            });
        }
        Page<DevopsMergeRequestE> page = devopsMergeRequestRepository
                .getByGitlabProjectId(gitLabProjectId, pageRequest);
        if (StringUtil.isNotEmpty(state)) {
            page = devopsMergeRequestRepository
                    .getMergeRequestList(gitLabProjectId, state, pageRequest);
        }
        List<MergeRequestDTO> pageContent = new ArrayList<>();
        List<DevopsMergeRequestE> content = page.getContent();
        if (content != null && !content.isEmpty()) {
            content.forEach(devopsMergeRequestE -> {
                MergeRequestDTO mergeRequestDTO = devopsMergeRequestToMergeRequest(
                        devopsMergeRequestE);
                pageContent.add(mergeRequestDTO);
            });
        }
        int total = count[0] + count[1] + count[2];
        Page<MergeRequestDTO> pageResult = new Page<>();
        BeanUtils.copyProperties(page, pageResult);
        pageResult.setContent(pageContent);
        Map<String, Object> result = new HashMap<>();
        result.put("mergeCount", count[0]);
        result.put("openCount", count[1]);
        result.put("closeCount", count[2]);
        result.put("totalCount", total);
        result.put("pageResult", pageResult);
        return result;
    }

    private MergeRequestDTO devopsMergeRequestToMergeRequest(DevopsMergeRequestE devopsMergeRequestE) {
        MergeRequestDTO mergeRequestDTO = new MergeRequestDTO();
        BeanUtils.copyProperties(devopsMergeRequestE, mergeRequestDTO);
        mergeRequestDTO.setProjectId(devopsMergeRequestE.getProjectId().intValue());
        mergeRequestDTO.setId(devopsMergeRequestE.getId().intValue());
        mergeRequestDTO.setIid(devopsMergeRequestE.getGitlabMergeRequestId().intValue());
        Long authorUserId = devopsGitRepository
                .getUserIdByGitlabUserId(devopsMergeRequestE.getAuthorId());
        Long gitlabMergeRequestId = devopsMergeRequestE.getGitlabMergeRequestId();
        Integer gitlabUserId = devopsGitRepository.getGitlabUserId();
        List<CommitDO> commitDOS = gitlabServiceClient.listCommits(
                devopsMergeRequestE.getProjectId().intValue(),
                gitlabMergeRequestId.intValue(), gitlabUserId).getBody();
        mergeRequestDTO.setCommits(ConvertHelper.convertList(commitDOS, CommitDTO.class));
        UserE authorUser = iamRepository.queryUserByUserId(authorUserId);
        if (authorUser != null) {
            AuthorDTO authorDTO = new AuthorDTO();
            authorDTO.setUsername(authorUser.getLoginName());
            authorDTO.setName(authorUser.getRealName());
            authorDTO.setId(authorUser.getId() == null ? null : authorUser.getId().intValue());
            authorDTO.setWebUrl(authorUser.getImageUrl());
            mergeRequestDTO.setAuthor(authorDTO);
        }
        return mergeRequestDTO;
    }

    @Override
    public DevopsBranchE queryByBranchNameAndCommit(String branchName, String commit) {
        DevopsBranchDO devopsBranchDO = new DevopsBranchDO();
        devopsBranchDO.setBranchName(branchName);
        devopsBranchDO.setCheckoutCommit(commit);
        return ConvertHelper.convert(devopsBranchMapper.selectOne(devopsBranchDO), DevopsBranchE.class);
    }

    @Override
    public CommitE getCommit(Integer gitLabProjectId, String commit, Integer userId) {
        CommitE commitE = new CommitE();
        BeanUtils.copyProperties(
                gitlabServiceClient.getCommit(gitLabProjectId, commit, userId).getBody(),
                commitE);
        return commitE;
    }

    @Override
    public List<DevopsBranchE> listDevopsBranchesByAppIdAndBranchName(Long appId, String branchName) {
        DevopsBranchDO devopsBranchDO = new DevopsBranchDO();
        devopsBranchDO.setAppId(appId);
        devopsBranchDO.setBranchName(branchName);
        return ConvertHelper.convertList(devopsBranchMapper.select(devopsBranchDO), DevopsBranchE.class);
    }

    @Override
    public List<DevopsBranchE> listDevopsBranchesByAppId(Long appId) {
        DevopsBranchDO devopsBranchDO = new DevopsBranchDO();
        devopsBranchDO.setAppId(appId);
        return ConvertHelper.convertList(devopsBranchMapper.select(devopsBranchDO), DevopsBranchE.class);
    }

    private Integer sortTag(TagDO a, TagDO b) {
        TagNodeDO tagA = TagNodeDO.tagNameToTagNode(a.getName());
        TagNodeDO tagB = TagNodeDO.tagNameToTagNode(b.getName());
        if (tagA != null && tagB != null) {
            return tagA.compareTo(tagB) * -1;
        } else if (tagA == null && tagB != null) {
            return 1;
        } else if (tagA != null) {
            return -1;
        } else {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    }
}