package com.example.rbac.customer.service;

import com.example.rbac.customer.dto.CustomerDtos.CreateCustomerRequest;
import com.example.rbac.customer.dto.CustomerDtos.UpdateCustomerRequest;
import com.example.rbac.customer.exception.NotFoundException;
import com.example.rbac.customer.model.Customer;
import com.example.rbac.customer.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    /** 列表 / 检索（分页）：q 为空返回全部，否则按 姓名/公司/电话/邮箱 模糊匹配。 */
    public Page<Customer> list(String q, int page, int size) {
        Pageable pg = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
        if (q == null || q.isBlank()) {
            return repository.findAll(pg);
        }
        return repository.search(q.trim(), pg);
    }

    public Customer get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("customer not found: " + id));
    }

    @Transactional
    public Customer create(CreateCustomerRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        Customer c = new Customer();
        c.setName(req.name());
        c.setCompany(req.company());
        c.setPhone(req.phone());
        c.setEmail(req.email());
        c.setStatus(req.status() == null || req.status().isBlank() ? "lead" : req.status());
        c.setNotes(req.notes());
        return repository.save(c);
    }

    @Transactional
    public Customer update(Long id, UpdateCustomerRequest req) {
        Customer c = get(id);
        if (req.name() != null) c.setName(req.name());
        if (req.company() != null) c.setCompany(req.company());
        if (req.phone() != null) c.setPhone(req.phone());
        if (req.email() != null) c.setEmail(req.email());
        if (req.status() != null) c.setStatus(req.status());
        if (req.notes() != null) c.setNotes(req.notes());
        return repository.save(c);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("customer not found: " + id);
        }
        repository.deleteById(id);
    }

    /**
     * 幂等播种。表非空则跳过。
     * 若环境变量 CRM_SEED_CSV 指向一个存在的 CSV 文件，则从该文件注入真实通讯录（替代示例种子）；
     * 否则播种 3 条示例客户，便于直接演示 CRM 列表与权限门禁。
     * 注意：CSV 中的手机号/邮箱等为真实 PII，仅用于本地实例，绝不入库/提交。
     */
    public void seedIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        String csv = System.getenv("CRM_SEED_CSV");
        if (csv != null && !csv.isBlank() && Files.exists(Path.of(csv))) {
            int n = seedFromCsv(Path.of(csv));
            if (n > 0) {
                System.out.println("[customer] seeded " + n + " customers from CSV: " + csv);
                return;
            }
            System.err.println("[customer] CRM_SEED_CSV 命中但解析为 0 行，回退示例种子");
        }
        seedSamples();
    }

    /**
     * 从外部 CSV 注入客户。字段顺序：来源,微信备注名,微信ID,ID类型,通讯录姓名,手机号,归属地,运营商,邮箱,匹配方式。
     * 映射：name=通讯录姓名(缺失回退微信备注名)；phone=手机号；email=邮箱；status=lead；notes=来源/微信ID/ID类型/归属地/运营商/匹配方式。
     * 返回注入条数，异常时返回 0（调用方回退到示例种子）。
     */
    private int seedFromCsv(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == '﻿') {
                content = content.substring(1); // 去除 UTF-8 BOM
            }
            String[] lines = content.split("\r?\n");
            if (lines.length <= 1) {
                return 0;
            }
            List<Customer> batch = new ArrayList<>();
            int total = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                if (f.length < 10) {
                    continue;
                }
                Customer c = new Customer();
                c.setName(firstNonBlank(f[4], f[1], "联系人"));
                c.setPhone(blankToNull(f[5]));
                c.setEmail(blankToNull(f[8]));
                c.setStatus("lead");
                c.setNotes(buildNotes(f));
                batch.add(c);
                if (batch.size() >= 500) {
                    repository.saveAll(batch);
                    total += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                repository.saveAll(batch);
                total += batch.size();
            }
            return total;
        } catch (Exception e) {
            System.err.println("[customer] CSV 导入失败，回退示例种子: " + e.getMessage());
            return 0;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String buildNotes(String[] f) {
        StringBuilder sb = new StringBuilder();
        appendNote(sb, "来源", f[0]);
        appendNote(sb, "微信ID", f[2]);
        appendNote(sb, "ID类型", f[3]);
        appendNote(sb, "归属地", f[6]);
        appendNote(sb, "运营商", f[7]);
        appendNote(sb, "匹配方式", f[9]);
        String s = sb.toString();
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static void appendNote(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(key).append(':').append(value.trim());
        }
    }

    private void seedSamples() {
        repository.save(sample("张伟", "云启科技", "13800001111", "zhangwei@yunqi.com", "customer", "年度续约客户，对接人李经理"));
        repository.save(sample("李娜", "恒通物流", "13900002222", "lina@hengtong.com", "prospect", "Q3 入围招标，待技术方案"));
        repository.save(sample("王强", "明远制造", "13700003333", "wangqiang@mingyuan.com", "lead", "展会留资，未跟进"));
        System.out.println("[customer] seeded 3 sample customers");
    }

    private Customer sample(String name, String company, String phone, String email, String status, String notes) {
        Customer c = new Customer();
        c.setName(name);
        c.setCompany(company);
        c.setPhone(phone);
        c.setEmail(email);
        c.setStatus(status);
        c.setNotes(notes);
        return c;
    }
}
