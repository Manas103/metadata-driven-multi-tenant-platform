package com.manas.metaplatform.admin;

import com.manas.metaplatform.bookofrecord.generator.BookOfRecordDataSeeder;
import com.manas.metaplatform.domain.Tenant;
import com.manas.metaplatform.generator.DataSeeder;
import com.manas.metaplatform.generator.SharingDataSeeder;
import com.manas.metaplatform.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads the synthetic demo data into an empty database at startup, so a fresh deployment
 * has something for the console to show: the 6 metadata tenants, the role-hierarchy
 * sharing tenant, and a small ledger. Off unless {@code platform.demo-data=true}; does
 * nothing if any tenant already exists, so a restart never seeds twice.
 */
@Component
@ConditionalOnProperty(name = "platform.demo-data", havingValue = "true")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);
    static final int LEDGER_ACCOUNTS = 20;

    private final TenantRepository tenants;
    private final DataSeeder dataSeeder;
    private final SharingDataSeeder sharingDataSeeder;
    private final BookOfRecordDataSeeder ledgerSeeder;

    public DemoDataLoader(TenantRepository tenants, DataSeeder dataSeeder, SharingDataSeeder sharingDataSeeder,
                          BookOfRecordDataSeeder ledgerSeeder) {
        this.tenants = tenants;
        this.dataSeeder = dataSeeder;
        this.sharingDataSeeder = sharingDataSeeder;
        this.ledgerSeeder = ledgerSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenants.count() > 0) {
            log.info("demo data: database already has tenants, not seeding");
            return;
        }
        long start = System.currentTimeMillis();
        dataSeeder.seed(42L);
        sharingDataSeeder.seed(555L);
        Tenant fund = tenants.save(new Tenant("LedgerFund"));
        ledgerSeeder.seed(fund.getId(), 9001L, LEDGER_ACCOUNTS);
        log.info("demo data: seeded in {} ms", System.currentTimeMillis() - start);
    }
}
