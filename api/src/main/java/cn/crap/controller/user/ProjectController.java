package cn.crap.controller.user;

import cn.crap.adapter.ProjectAdapter;
import cn.crap.dto.LoginInfoDto;
import cn.crap.dto.ProjectDto;
import cn.crap.enumeration.ProjectStatus;
import cn.crap.enumeration.UserType;
import cn.crap.framework.JsonResult;
import cn.crap.framework.MyException;
import cn.crap.framework.base.BaseController;
import cn.crap.framework.interceptor.AuthPassport;
import cn.crap.model.mybatis.Project;
import cn.crap.model.mybatis.ProjectCriteria;
import cn.crap.service.ISearchService;
import cn.crap.service.mybatis.custom.CustomErrorService;
import cn.crap.service.mybatis.custom.CustomModuleService;
import cn.crap.service.mybatis.custom.CustomProjectService;
import cn.crap.service.mybatis.custom.CustomProjectUserService;
import cn.crap.service.mybatis.imp.*;
import cn.crap.springbeans.Config;
import cn.crap.utils.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/user/project")
public class ProjectController extends BaseController {
@Autowired
private MybatisProjectService projectService;
@Autowired
private MybatisModuleService moduleService;
@Autowired
private Config config;
@Autowired
private ISearchService luceneService;
@Autowired
private CustomErrorService customErrorService;
@Autowired
private MybatisUserService userService;
@Autowired
private CustomModuleService customModuleService;
@Autowired
private CustomProjectUserService customProjectUserService;
	@Autowired
	private CustomProjectService customProjectService;
	@Autowired
	private MybatisProjectUserService projectUserService;
	@Autowired
	private MybatisRoleService roleService;

	@RequestMapping("/list.do")
	@ResponseBody
	@AuthPassport
	public JsonResult list(@ModelAttribute Project project, @RequestParam(defaultValue="1") int currentPage,
						   @RequestParam(defaultValue="false") boolean myself) throws MyException{
		Assert.isTrue(currentPage > 0);
		Page page= new Page(SIZE, currentPage);

		// 普通用户，管理员我的项目菜单只能查看自己的项目
		LoginInfoDto user = Tools.getUser();
		List<Project> models = null;
		List<ProjectDto> dtos = null;
		if( Tools.getUser().getType() == UserType.USER.getType() || myself){
			page.setAllRow(customProjectService.countProjectByUserIdName(user.getId(), project.getName()));
			models = customProjectService.pageProjectByUserIdName(user.getId(), project.getName(), page);
			dtos = ProjectAdapter.getDto(models);
			return new JsonResult(1,dtos, page);
		}else{
			Map<String,Object> map = null;
			ProjectCriteria example = new ProjectCriteria();
			ProjectCriteria.Criteria criteria = example.createCriteria();
			if (project.getName() != null){
				criteria.andNameLike("%" + project.getName() +"%");
			}
			example.setLimitStart(page.getStart());
			example.setMaxResults(page.getSize());
			example.setOrderByClause(TableField.SORT.SEQUENCE_DESC);
			page.setAllRow(projectService.countByExample(example));
			models = projectService.selectByExample(example);
		}
		return new JsonResult(1,dtos, page);
	}

	@RequestMapping("/detail.do")
	@ResponseBody
	@AuthPassport
	public JsonResult detail(@ModelAttribute Project project) throws MyException{
		Project model;
		if(!project.getId().equals(Const.NULL_ID)){
			model= projectCache.get(project.getId());
			hasPermission(model);
		}else{
			model=new Project();
		}
		return new JsonResult(1,model);
	}


	@RequestMapping("/addOrUpdate.do")
	@ResponseBody
	public JsonResult addOrUpdate(@ModelAttribute Project project) throws Exception{
		// 系统数据，不允许删除
		if(project.getId().equals("web"))
			throw new MyException("000009");

		Project model;
		LoginInfoDto user = Tools.getUser();

		// 修改
		if(!MyString.isEmpty(project.getId())){
			model= projectCache.get(project.getId());
			hasPermission(model);

			// 不允许转移项目
			project.setUserId(model.getUserId());

			// 普通用户不能推荐项目，将项目类型修改为原有类型
			if( Tools.getUser().getType() == UserType.USER.getType()){
				project.setStatus(model.getStatus());
			}

			customProjectService.update(project , "项目" , "");
		}

		// 新增
		else{
			project.setUserId(user.getId());
			// 普通用户不能推荐项目
			if( Tools.getUser().getType() == UserType.USER.getType()){
				project.setStatus(Byte.valueOf(ProjectStatus.COMMON.getStatus()+""));
			}

			projectService.insert(project);
		}

		// 清楚缓存
		projectCache.del(project.getId());

		// 刷新用户权限 将用户信息存入缓存
		userCache.add(user.getId(), new LoginInfoDto(userService.selectByPrimaryKey(user.getId()), roleService, customProjectService, projectUserService));
		return new JsonResult(1,project);
	}


	@RequestMapping("/delete.do")
	@ResponseBody
	public JsonResult delete(@ModelAttribute Project project) throws Exception{
		// 系统数据，不允许删除
		if(project.getId().equals("web"))
			throw new MyException("000009");


		Project model= projectCache.get(project.getId());
		hasPermission(model);


		// 只有子模块数量为0，才允许删除项目
		if(customModuleService.countByProjectId(model.getId()) > 0){
			throw new MyException("000023");
		}

		// 只有错误码数量为0，才允许删除项目
		if(customErrorService.countByProjectId(model.getId()) > 0){
			throw new MyException("000033");
		}

		// 只有项目成员数量为0，才允许删除项目
		if(customProjectUserService.countByProjectId(model.getId())>0){
			throw new MyException("000038");
		}

		projectCache.del(project.getId());
		customProjectService.delete(project.getId(), "项目", "");
		return new JsonResult(1,null);
	}

	@RequestMapping("/changeSequence.do")
	@ResponseBody
	@AuthPassport
	public JsonResult changeSequence(@RequestParam String id,@RequestParam String changeId) throws MyException {
		Project change = projectCache.get(changeId);
		Project model = projectCache.get(id);

		hasPermission(change);
		hasPermission(model);

		int modelSequence = model.getSequence();
		model.setSequence(change.getSequence());
		change.setSequence(modelSequence);

		projectService.update(model);
		projectService.update(change);

		return new JsonResult(1, null);
	}

	@ResponseBody
	@RequestMapping("/rebuildIndex.do")
	@AuthPassport
	public JsonResult rebuildIndex(@RequestParam String projectId) throws Exception {
		Project model= projectCache.get(projectId);
		hasPermission(model);
		return new JsonResult(1, luceneService.rebuildByProjectId(projectId));
	}
}
