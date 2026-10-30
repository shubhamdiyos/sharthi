package com.saathi.tenancy.web;

import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.service.ServiceabilityService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The category picker: one city's activated slice of the global taxonomy (§4.1, §10.5.3).
 *
 * <p>Documented under §10.5.3's storefront heading and attributed there to {@code catalog/}, but it
 * lives in the tenancy module because that is where its tables are: the answer is a join of
 * {@code tenant_category} onto {@code category}, and §3's rule is that a module owns the tables its
 * queries read. Where a row of the document and where a table of the schema disagree, the schema is
 * the one with a foreign key behind it.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CategoryController {

    private final ServiceabilityService serviceability;

    @GetMapping("/categories")
    public List<CategoryView> categories(
        @RequestParam(name = "city_code", required = false) @NotBlank @Size(max = 32) String cityCode) {
        Tenant tenant = serviceability.liveTenant(cityCode);
        return serviceability.activatedCategories(tenant).stream().map(CategoryView::of).toList();
    }
}
